package com.skylightlim.umtimetable

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Build
import android.text.Html
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "UMTimetable"

/** Fetches what the Spectrum tabs show, and notifies about what's new. Runs on the phone only. */
object SpectrumSync {
    private val running = AtomicBoolean(false)

    // Spectrum requests are independent once the course list is known, so they run side by side.
    // Two pools: the per-forum requests run inside a sync task and must never wait on their own pool.
    private val syncPool = Executors.newFixedThreadPool(4)
    private val requestPool = Executors.newFixedThreadPool(6)

    /** News covers this many days (a month); older announcements are on each course's page. */
    const val NEWS_DAYS = 30
    private const val NEWS_PAGE = 50
    /** A safety stop: 500 discussions in a month would be a forum posting to many groups every day. */
    private const val NEWS_MAX_PAGES = 10

    /** Like Future.get(), but rethrows the task's own exception so the error handling below still sees it. */
    private fun <T> Future<T>.await(): T = try {
        get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    /**
     * A full sync, or only today's attendance sessions. Returns an error message, or null on success.
     * [background] runs come from the job or an alarm, while the app isn't on screen.
     */
    fun run(c: Context, attendanceOnly: Boolean = false, background: Boolean = false): String? {
        val token = SpectrumStore.token(c) ?: return "Not signed in"
        if (!running.compareAndSet(false, true)) return null
        val started = android.os.SystemClock.elapsedRealtime()
        var error: String? = null
        var blocked = false
        try {
            val api = Api(token, SpectrumStore.userId(c))
            val courses = if (attendanceOnly && SpectrumStore.read(c, "courses") != null) {
                SpectrumStore.array(c, "courses")
            } else {
                fetchCourses(api).also { SpectrumStore.write(c, "courses", it.toString()) }
            }
            MayaImport.fillNames(c, courses)
            val news = if (attendanceOnly) null else syncPool.submit(Callable { fetchNews(c, api, courses) })
            val due = if (attendanceOnly) null else syncPool.submit(Callable { fetchDue(api, courses) })
            if (!attendanceOnly) syncGrades(c, api, courses)
            val attendance = syncPool.submit(Callable { fetchAttendance(c, api, courses, fresh = !attendanceOnly) })
            // Notifying and saving stay on this thread, in the same order as before.
            if (news != null && due != null) {
                val n = news.await()
                notifyNews(c, n)
                SpectrumStore.write(c, "news", n.toString())
                val d = due.await()
                notifyNewDue(c, d)
                SpectrumStore.write(c, "due", d.toString())
                // The first sync after signing in only records what exists; later ones notify.
                SpectrumStore.setFlag(c, "primed", true)
            }
            val a = attendance.await()
            SpectrumStore.write(c, "attendance", a.toString())
            notifyAttendance(c, a)
            Log.i(TAG, "Spectrum sync ok in ${android.os.SystemClock.elapsedRealtime() - started} ms (attendanceOnly=$attendanceOnly): ${courses.length()} courses, ${a.length()} attendance sessions today")
        } catch (e: Spectrum.Error) {
            error = if (e.code == "invalidtoken" || e.code == "accessexception") "Spectrum sign-in expired. Sign in again." else e.message
            Log.w(TAG, "Spectrum error ${e.code}: ${e.message}")
        } catch (e: IOException) {
            // The phone can block an app's network while it's in the background (background data off,
            // Data Saver). That isn't a Spectrum problem, so the page explains the setting instead.
            blocked = background && networkBlocked(c)
            error = "Couldn't reach Spectrum. Check your connection."
            Log.w(TAG, "Spectrum unreachable (blockedInBackground=$blocked): ${e.message}")
        } catch (e: Exception) {
            error = "Sync failed: ${e.message}"
            Log.w(TAG, "Spectrum sync failed", e)
        } finally {
            if (blocked) {
                SpectrumStore.setFlag(c, "bgBlocked", true)
            } else {
                if (background && error == null) SpectrumStore.setFlag(c, "bgBlocked", false)
                if (!attendanceOnly || error != null) SpectrumStore.recordSync(c, error)
            }
            running.set(false)
        }
        Reminders.schedule(c)
        AttendanceWatch.schedule(c)
        TimetableWidget.updateAll(c)
        return error
    }

    /** One course page, flattened for the Courses tab and cached for offline use. */
    fun courseContents(c: Context, courseId: Int): String {
        val token = SpectrumStore.token(c) ?: return JSONObject().put("error", "Not signed in.").toString()
        val cacheName = "contents-$courseId"
        return try {
            val sections = Spectrum.call(token, "core_course_get_contents", mapOf("courseid" to courseId)) as JSONArray
            normalizeContents(sections).toString().also { SpectrumStore.write(c, cacheName, it) }
        } catch (e: Exception) {
            Log.w(TAG, "course contents failed: ${e.message}")
            SpectrumStore.read(c, cacheName) ?: JSONObject().put("error", "Couldn't load this course from Spectrum.").toString()
        }
    }

    /** core_course_get_contents → [{ id, name, summary, modules: [{ kind, name, label, url, files, html, dates, availability }] }] */
    fun normalizeContents(sections: JSONArray): JSONArray {
        val out = JSONArray()
        for (s in sections.objects()) {
            val modules = JSONArray()
            for (m in (s.optJSONArray("modules") ?: JSONArray()).objects()) {
                if (m.optInt("uservisible", 1) == 0) continue
                val modname = m.optString("modname")
                val files = JSONArray()
                val contents = m.optJSONArray("contents") ?: JSONArray()
                for (f in contents.objects()) {
                    if (f.optString("type") != "file") continue
                    val path = f.optString("filepath", "/").trim('/')
                    files.put(file(f.optString("filename").let { if (path.isEmpty()) it else "$path/$it" }, f))
                }
                val kind = when (modname) {
                    "resource" -> "file"
                    "folder" -> "folder"
                    "url" -> "url"
                    "label" -> "label"
                    else -> "other"
                }
                val url = if (kind == "url") contents.optJSONObject(0)?.optString("fileurl")?.takeIf { it.isNotBlank() } ?: m.optString("url") else m.optString("url")
                // Text blocks keep their content in "description"; other activities may show one on the course page.
                // A folder's description is its inline file list, which the files already cover.
                val html = if (modname == "folder") "" else m.optString("description")
                val dates = JSONArray((m.optJSONArray("dates") ?: JSONArray()).objects().map { JSONObject().put("label", it.optString("label")).put("time", it.optLong("timestamp") * 1000) })
                modules.put(
                    JSONObject().put("kind", kind).put("name", plain(m.optString("name"))).put("label", moduleLabel(modname))
                        .put("url", url).put("files", files).put("html", html).put("dates", dates)
                        // Spectrum's access rules under an activity ("Not available unless: …"), as HTML.
                        .put("availability", m.optString("availabilityinfo")),
                )
            }
            val summary = s.optString("summary")
            if (modules.length() > 0 || plain(summary).isNotEmpty()) {
                out.put(JSONObject().put("id", s.optInt("id")).put("name", plain(s.optString("name")).ifBlank { "General" }).put("summary", summary).put("modules", modules))
            }
        }
        return out
    }

    @Suppress("DEPRECATION") // NetworkInfo is still the only per-app view of "blocked"
    fun networkBlocked(c: Context): Boolean =
        c.getSystemService(ConnectivityManager::class.java).activeNetworkInfo?.detailedState == NetworkInfo.DetailedState.BLOCKED

    // --- fetching ---------------------------------------------------------------------------

    private class Api(val token: String, val userId: Int) {
        fun call(fn: String, params: Map<String, Any?> = emptyMap()) = Spectrum.call(token, fn, params)
    }

    private fun fetchCourses(api: Api): JSONArray {
        var raw = runCatching {
            (api.call("core_course_get_enrolled_courses_by_timeline_classification", mapOf("classification" to "inprogress")) as JSONObject)
                .getJSONArray("courses").objects()
        }.getOrDefault(emptyList())
        if (raw.isEmpty()) {
            val cutoff = System.currentTimeMillis() / 1000 - 30 * 86_400
            raw = (api.call("core_enrol_get_users_courses", mapOf("userid" to api.userId)) as JSONArray).objects()
                .filter { it.optInt("visible", 1) != 0 && (it.optLong("enddate") == 0L || it.optLong("enddate") > cutoff) }
        }
        return JSONArray(
            raw.map {
                JSONObject().put("id", it.getInt("id")).put("name", plain(it.optString("fullname")))
                    .put("short", plain(it.optString("shortname")))
                    .put("code", Spectrum.courseCode(it.optString("shortname"), it.optString("fullname")) ?: "")
            }.sortedWith(compareBy({ it.getString("code").ifEmpty { "~" } }, { it.getString("name") })),
        )
    }

    /**
     * A course-wide announcement meant for other occurrences only: its subject names occurrences, none of
     * them [occ], or, when the subject names none, its opening ("Dear OCC7, OCC8, and OCC9 Students")
     * does. One naming the user's occurrence, or none at all, is for everyone.
     */
    fun addressedToOthers(subject: String, text: String, occ: Int): Boolean =
        AttendanceMatch.occurrences(subject).ifEmpty { AttendanceMatch.occurrences(text.take(160)) }.let { occ > 0 && it.isNotEmpty() && occ !in it }

    /** "GROUP 10 2026/2027_1", idnumber "2026@2027@1@10" (session@semester@occurrence) → 10. */
    fun occurrenceOf(group: JSONObject): Int? =
        group.optString("idnumber").split('@').takeIf { it.size == 4 }?.last()?.toIntOrNull()
            ?: Regex("""(?i)\bgroup\s*(\d+)""").find(group.optString("name"))?.groupValues?.get(1)?.toInt()

    private fun fetchNews(c: Context, api: Api, courses: JSONArray): JSONArray {
        val byId = courses.objects().associateBy { it.getInt("id") }
        if (byId.isEmpty()) return JSONArray()
        // The user's occurrence group in each course, fetched alongside the forums.
        val groupsRequest = requestPool.submit(Callable {
            runCatching { (api.call("core_group_get_course_user_groups", mapOf("courseid" to 0, "userid" to api.userId)) as JSONObject).getJSONArray("groups").objects() }
                .onFailure { Log.w(TAG, "groups failed: ${it.message}") }.getOrDefault(emptyList())
        })
        val forums = api.call("mod_forum_get_forums_by_courses", mapOf("courseids" to byId.keys.toList())) as JSONArray
        val newsForums = forums.objects().filter { it.optString("type") == "news" && byId.containsKey(it.optInt("course")) }
        val since = System.currentTimeMillis() / 1000 - NEWS_DAYS * 86_400L
        // Each course's announcement forum at once.
        val requests = newsForums.map { f -> f to requestPool.submit(Callable { discussions(api, f.getInt("id"), since) }) }
        val groups = groupsRequest.await()
        val myGroups = groups.groupBy({ it.optInt("courseid") }, { it.optInt("id") })
        // The user's occurrence in each course, saved for building the timetable from TimeEdit.
        if (groups.isNotEmpty()) {
            val occurrences = JSONObject()
            for (g in groups) {
                val code = byId[g.optInt("courseid")]?.optString("code").orEmpty()
                val occ = occurrenceOf(g) ?: continue
                if (code.isNotEmpty() && !occurrences.has(code)) occurrences.put(code, occ)
            }
            SpectrumStore.write(c, "occurrences", occurrences.toString())
        }
        // The user's occurrence in each course: from the groups just read, else the timetable's.
        val savedOccurrences = SpectrumStore.occurrences(c)
        val timetable = Timetable.load(c)
        fun myOcc(code: String) = savedOccurrences.optInt(code).takeIf { it > 0 } ?: timetable.courses.firstOrNull { it.code == code }?.occ ?: 0
        val items = mutableListOf<JSONObject>()
        for ((f, request) in requests) {
            val course = byId.getValue(f.optInt("course"))
            val discussions = request.await() ?: continue
            for (d in discussions.objects()) {
                val created = d.optLong("created").takeIf { it > 0 } ?: d.optLong("timemodified")
                if (created < since) continue
                val subject = plain(d.optString("subject").ifBlank { d.optString("name") })
                val group = d.optInt("groupid", -1)
                items += JSONObject()
                    .put("id", d.optInt("discussion", d.optInt("id")))
                    .put("courseId", course.getInt("id"))
                    .put("code", course.getString("code"))
                    .put("course", course.getString("name"))
                    .put("subject", subject)
                    .put("html", d.optString("message"))
                    .put("author", d.optString("userfullname"))
                    .put("time", created * 1000)
                    .put("attachments", JSONArray((d.optJSONArray("attachments") ?: JSONArray()).objects().map { file(it.optString("filename"), it) }))
                    // Posted to the user's group, or in a course where they have none, or to everyone (group -1)
                    // unless it's addressed to other occurrences only ("… for OCC7, OCC8 and OCC9").
                    .put("forMe", if (group > 0) myGroups[course.getInt("id")]?.contains(group) != false
                        else !addressedToOthers(subject, plain(d.optString("message")), myOcc(course.getString("code"))))
            }
        }
        // "Post a copy to all groups" creates one identical discussion per group, and with visible
        // groups a student sees every copy. Keep one of each: the user's own copy if there is one.
        val unique = items.sortedWith(compareByDescending<JSONObject> { it.getBoolean("forMe") }.thenByDescending { it.getLong("time") })
            .distinctBy { Triple(it.getInt("courseId"), it.getString("subject"), plain(it.getString("html"))) }
            .sortedByDescending { it.getLong("time") }
        Log.i(TAG, "news: ${unique.size} announcements, ${unique.count { !it.getBoolean("forMe") }} for other occurrences")
        return JSONArray(unique)
    }

    /**
     * One announcement forum's discussions with posts since [since] (seconds), or null if Spectrum
     * refuses (logged, not fatal). Spectrum lists pinned discussions first, then the rest by latest
     * post, newest first; a page that reaches back past [since] is the last one needed, since a
     * discussion can't start after its latest post.
     */
    private fun discussions(api: Api, forumId: Int, since: Long): JSONArray? = runCatching {
        val all = mutableListOf<JSONObject>()
        for (page in 0 until NEWS_MAX_PAGES) {
            val batch = discussionsPage(api, forumId, page).objects()
            all += batch
            if (batch.size < NEWS_PAGE || batch.any { !it.optBoolean("pinned") && it.optLong("timemodified") < since }) break
        }
        JSONArray(all)
    }.onFailure { Log.w(TAG, "announcements for forum $forumId failed: ${it.message}") }.getOrNull()

    private fun discussionsPage(api: Api, forumId: Int, page: Int): JSONArray =
        (api.call("mod_forum_get_forum_discussions", mapOf("forumid" to forumId, "sortorder" to -1, "page" to page, "perpage" to NEWS_PAGE)) as JSONObject)
            .getJSONArray("discussions")

    private val gradesRunning = AtomicBoolean(false)

    /** Called on the main thread when new grades are saved, while a screen is listening. */
    @Volatile var onGrades: (() -> Unit)? = null

    /**
     * Grades load beside the sync and never hold it up: Spectrum can take 30 s over one course's
     * grade report (GIG1003's timed out on 13 Oct), and attendance must not wait for that.
     */
    private fun syncGrades(c: Context, api: Api, courses: JSONArray) {
        if (!gradesRunning.compareAndSet(false, true)) return
        syncPool.execute {
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                val g = fetchGrades(c, api, courses)
                notifyGrades(c, g)
                SpectrumStore.write(c, "grades", g.toString())
                Log.i(TAG, "grades: ${g.length()} items in ${android.os.SystemClock.elapsedRealtime() - started} ms")
                onGrades?.let { android.os.Handler(android.os.Looper.getMainLooper()).post { onGrades?.invoke() } }
            } catch (e: Exception) {
                Log.w(TAG, "grades failed", e)
            } finally {
                gradesRunning.set(false)
            }
        }
    }

    /**
     * The user's grade items in every course (gradereport_user_get_grade_items), all courses at once.
     * Items the lecturer hides, and category subtotals, are left out; unmarked items are kept so the
     * page can say what's still to come.
     */
    private fun fetchGrades(c0: Context, api: Api, courses: JSONArray): JSONArray {
        // A course whose report fails this time keeps what was saved before.
        val before = SpectrumStore.array(c0, "grades").objects().groupBy { it.optInt("courseId") }
        val requests = courses.objects().map { c ->
            c to requestPool.submit(Callable {
                runCatching { api.call("gradereport_user_get_grade_items", mapOf("courseid" to c.getInt("id"), "userid" to api.userId)) as JSONObject }
                    .onFailure { Log.w(TAG, "grades for course ${c.getInt("id")} failed: ${it.message}") }.getOrNull()
            })
        }
        val out = JSONArray()
        for ((c, request) in requests) {
            val items = request.await()?.optJSONArray("usergrades")?.optJSONObject(0)?.optJSONArray("gradeitems")
            if (items == null) {
                before[c.getInt("id")]?.forEach { out.put(it) }
                continue
            }
            for (i in items.objects()) {
                val type = i.optString("itemtype")
                if (type == "category" || i.optBoolean("gradeishidden") || i.optBoolean("gradehiddenbydate")) continue
                val module = i.optString("itemmodule")
                val cmid = i.optInt("cmid")
                out.put(
                    JSONObject()
                        .put("id", i.getInt("id"))
                        .put("courseId", c.getInt("id"))
                        .put("code", c.getString("code"))
                        .put("course", c.getString("name"))
                        .put("total", type == "course")
                        .put("name", if (type == "course") "Course total" else plain(i.optString("itemname")))
                        .put("kind", moduleLabel(module))
                        .put("raw", if (i.isNull("graderaw")) JSONObject.NULL else i.getDouble("graderaw"))
                        .put("max", i.optDouble("grademax"))
                        // A scale grade ("Pass", "Excellent") is a word, not a number.
                        .put("word", if (i.optInt("scaleid") > 0) plain(i.optString("gradeformatted")) else "")
                        .put("percent", percent(plain(i.optString("percentageformatted"))))
                        .put("feedback", i.optString("feedback"))
                        .put("gradedAt", i.optLong("gradedategraded") * 1000)
                        .put("url", when {
                            type == "course" -> "${Spectrum.SITE}/grade/report/user/index.php?id=${c.getInt("id")}"
                            cmid > 0 && module.isNotEmpty() -> "${Spectrum.SITE}/mod/$module/view.php?id=$cmid"
                            else -> ""
                        }),
                )
            }
        }
        return out
    }

    private fun fetchDue(api: Api, courses: JSONArray): JSONArray {
        val now = System.currentTimeMillis() / 1000
        val codes = courses.objects().associate { it.getInt("id") to it.getString("code") }
        val params = mapOf("timesortfrom" to now - 14 * 86_400, "timesortto" to now + 120 * 86_400, "limitnum" to 50, "limittononsuspendedevents" to true)
        val res = api.call("core_calendar_get_action_events_by_timesort", params) as JSONObject
        val out = JSONArray()
        for (e in (res.optJSONArray("events") ?: JSONArray()).objects()) {
            if (e.optString("modulename") == "attendance") continue
            val course = e.optJSONObject("course")
            val courseId = course?.optInt("id") ?: e.optInt("courseid")
            val due = e.optLong("timesort").takeIf { it > 0 } ?: e.optLong("timestart")
            out.put(
                JSONObject()
                    .put("id", e.getInt("id"))
                    .put("name", plain(e.optString("activityname").ifBlank { e.optString("name") }))
                    .put("code", codes[courseId]?.takeIf { it.isNotEmpty() } ?: Spectrum.courseCode(course?.optString("shortname"), course?.optString("fullname")) ?: "")
                    .put("course", plain(course?.optString("fullname") ?: ""))
                    .put("due", due * 1000)
                    .put("url", e.optString("url"))
                    .put("moduleLabel", listOf(moduleLabel(e.optString("modulename")), e.optJSONObject("action")?.optString("name").orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
                    .put("overdue", e.optBoolean("overdue", false) || due < now),
            )
        }
        return out
    }

    /**
     * Today's attendance sessions for the user's classes. Spectrum exposes no mod_attendance_* web
     * services, but sessions are calendar events. The calendar also lists other groups' sessions when
     * their activity isn't restricted to a group, and leaves out sessions created without an event
     * (one WIX1002 tutorial's activity had none in five weeks), so each course's attendance activities are
     * matched to the user's classes ([AttendanceMatch]): other groups' sessions are dropped, and a class
     * whose own session isn't listed gets its activity's page, marked `listed: false`.
     */
    private fun fetchAttendance(c: Context, api: Api, courses: JSONArray, fresh: Boolean): JSONArray {
        val now = System.currentTimeMillis()
        val dayStart = startOfDay(now)
        val res = api.call(
            "core_calendar_get_calendar_events",
            mapOf("events" to mapOf("courseids" to courses.objects().map { it.getInt("id") }), "options" to mapOf("userevents" to false, "siteevents" to false, "timestart" to dayStart / 1000, "timeend" to dayStart / 1000 + 86_400)),
        ) as JSONObject
        val events = (res.optJSONArray("events") ?: JSONArray()).objects().filter { it.optString("modulename") == "attendance" }
        val byId = courses.objects().associateBy { it.getInt("id") }
        val idOf = courses.objects().associate { it.getString("code") to it.getInt("id") }
        val timetable = Timetable.load(c)
        // The classes attendance is taken at: Spectrum's occurrence's, for a course the user chose another of.
        val today = timetable.attendanceDay(Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_WEEK) - 1)
        val activities = attendanceActivities(c, api, today.mapNotNull { idOf[it.course.code] }.toSet() + events.map { it.optInt("courseid") }, fresh)
        fun classes(courseId: Int) = timetable.attendance.filter { it.course.code == byId[courseId]?.optString("code") }

        val sessions = mutableListOf<JSONObject>()
        fun add(id: String, courseId: Int, activity: AttendanceMatch.Activity, start: Long, end: Long, url: String) {
            val course = byId[courseId]
            sessions += JSONObject()
                .put("id", id)
                .put("attendanceId", activity.instance)
                .put("courseId", courseId)
                .put("code", course?.optString("code").orEmpty())
                .put("course", course?.optString("name").orEmpty())
                .put("name", activity.name)
                .put("start", start)
                .put("end", end)
                .put("url", url)
        }
        var others = 0
        for (e in events) {
            val courseId = e.optInt("courseid")
            val list = activities[courseId].orEmpty()
            val aid = e.optInt("instance")
            val activity = list.firstOrNull { it.instance == aid } ?: AttendanceMatch.Activity(0, aid, plain(e.optString("name")))
            val mine = classes(courseId)
            if (AttendanceMatch.owner(activity, list, mine.firstOrNull()?.course?.occ ?: 0, mine) == AttendanceMatch.Owner.OTHER) {
                others++
                continue
            }
            var cmid = activity.cmid.takeIf { it != 0 } ?: SpectrumStore.cmid(c, aid)
            if (cmid == 0 && aid != 0) {
                cmid = runCatching {
                    (api.call("core_course_get_course_module_by_instance", mapOf("module" to "attendance", "instance" to aid)) as JSONObject).getJSONObject("cm").getInt("id")
                }.getOrDefault(0)
                if (cmid != 0) SpectrumStore.putCmid(c, aid, cmid)
            }
            val start = e.getLong("timestart") * 1000
            add("e${e.getInt("id")}", courseId, activity, start, start + maxOf(e.optLong("timeduration"), 600) * 1000, if (cmid != 0) "${Spectrum.SITE}/mod/attendance/view.php?id=$cmid" else "${Spectrum.SITE}/my/")
        }
        var unlisted = 0
        for (s in today) {
            val courseId = idOf[s.course.code] ?: continue
            val mine = AttendanceMatch.pick(activities[courseId].orEmpty(), s.course.occ, s) ?: continue
            val start = dayStart + s.start * 60_000L
            val end = dayStart + s.end * 60_000L
            if (sessions.any { it.getInt("attendanceId") == mine.instance && it.getLong("start") < end && it.getLong("end") > start }) continue
            add("c${mine.cmid}-$start", courseId, mine, start, end, "${Spectrum.SITE}/mod/attendance/view.php?id=${mine.cmid}")
            sessions.last().put("listed", false)
            unlisted++
        }
        Log.i(TAG, "attendance: ${sessions.size} for your classes ($unlisted not in the calendar), $others for other groups left out")
        return JSONArray(sessions.sortedBy { it.getLong("start") })
    }

    /**
     * The attendance activities the user can open in each of [ids], from core_course_get_contents
     * (attendance modules only). Refetched on a full sync; the background attendance checks reuse them.
     */
    private fun attendanceActivities(c: Context, api: Api, ids: Set<Int>, fresh: Boolean): Map<Int, List<AttendanceMatch.Activity>> {
        val saved = runCatching { JSONObject(SpectrumStore.read(c, "attendance-activities") ?: "{}") }.getOrDefault(JSONObject())
        val got = java.util.concurrent.ConcurrentHashMap<Int, JSONArray>()
        ids.filter { fresh || !saved.has("$it") }.map { id ->
            thread {
                runCatching {
                    val sections = api.call(
                        "core_course_get_contents",
                        mapOf("courseid" to id, "options" to listOf(mapOf("name" to "modname", "value" to "attendance"), mapOf("name" to "excludecontents", "value" to true))),
                    ) as JSONArray
                    got[id] = JSONArray(
                        sections.objects().flatMap { (it.optJSONArray("modules") ?: JSONArray()).objects() }
                            .filter { it.optString("modname") == "attendance" && it.optBoolean("uservisible", true) }
                            .map { JSONObject().put("cmid", it.getInt("id")).put("instance", it.getInt("instance")).put("name", plain(it.optString("name"))) },
                    )
                }.onFailure { Log.w(TAG, "attendance activities of course $id: ${it.message}") }
            }
        }.forEach { it.join() }
        if (got.isNotEmpty()) {
            for ((id, list) in got) saved.put("$id", list)
            SpectrumStore.write(c, "attendance-activities", saved.toString())
        }
        return ids.associateWith { id ->
            saved.optJSONArray("$id")?.objects()?.map { AttendanceMatch.Activity(it.getInt("cmid"), it.getInt("instance"), it.getString("name")) }.orEmpty()
        }
    }

    // --- notifying --------------------------------------------------------------------------

    private fun notifyNews(c: Context, news: JSONArray) {
        val seen = SpectrumStore.stringSet(c, "seenNews")
        val fresh = news.objects().filter { it.getInt("id").toString() !in seen }
        if (SpectrumStore.flag(c, "primed")) {
            for (n in fresh.filter { it.optBoolean("forMe", true) }.take(6)) {
                Notify.post(
                    c, Notify.NEWS, "news-${n.getInt("id")}",
                    "${n.getString("code").ifEmpty { n.getString("course") }} · ${n.getString("subject")}",
                    plain(n.getString("html")).take(400),
                    Notify.openApp(c, "news"),
                )
            }
        }
        SpectrumStore.putStringSet(c, "seenNews", (seen + fresh.map { it.getInt("id").toString() }).toList().takeLast(500).toSet())
    }

    private fun notifyNewDue(c: Context, due: JSONArray) {
        val seen = SpectrumStore.stringSet(c, "seenDue")
        val fresh = due.objects().filter { it.getInt("id").toString() !in seen }
        if (SpectrumStore.flag(c, "primed")) {
            val now = System.currentTimeMillis()
            for (d in fresh.filter { it.getLong("due") > now }.take(4)) {
                Notify.post(
                    c, Notify.DUE, "due-${d.getInt("id")}",
                    "New on Spectrum · ${d.getString("code").ifEmpty { d.getString("course") }}",
                    "${d.getString("name")} · due ${SpectrumStore.dayWord(d.getLong("due"), now)} ${SpectrumStore.hm(d.getLong("due"))}",
                    Notify.openApp(c, "due"),
                )
            }
        }
        SpectrumStore.putStringSet(c, "seenDue", (seen + fresh.map { it.getInt("id").toString() }).toList().takeLast(500).toSet())
    }

    /** An attendance grade item: kept out of the Grades tab, its badge and its notifications. */
    fun isAttendance(g: JSONObject) = g.optString("kind") == "Attendance"

    /** A notification per newly released (or changed) mark; the first sync with grades only records them. */
    private fun notifyGrades(c: Context, grades: JSONArray) {
        val marked = grades.objects().filter { !it.isNull("raw") && !isAttendance(it) }
        val seen = SpectrumStore.stringSet(c, "seenGrades")
        if (SpectrumStore.flag(c, "gradesPrimed")) {
            for (g in marked.filter { gradeKey(it) !in seen }.take(4)) {
                Notify.post(
                    c, Notify.GRADES, "grade-${g.getInt("id")}",
                    "New mark · ${g.getString("code").ifEmpty { g.getString("course") }}",
                    "${g.getString("name")}: ${markText(g)}",
                    Notify.openApp(c, "grades"),
                )
            }
        }
        SpectrumStore.putStringSet(c, "seenGrades", marked.map(::gradeKey).toSet())
        SpectrumStore.setFlag(c, "gradesPrimed", true)
    }

    /** "100.00 %" → "100%", "85.50 %" → "85.5%"; anything else (e.g. "-") unchanged. */
    private fun percent(s: String): String =
        Regex("""^([\d.]+)\s*%$""").find(s.trim())?.groupValues?.get(1)?.toDoubleOrNull()?.let { "%.2f".format(it).trimEnd('0').trimEnd('.') + "%" } ?: s

    /** One mark's identity: a regrade counts as new. */
    fun gradeKey(g: JSONObject) = "${g.getInt("id")}:${g.opt("raw")}"

    /** "8.5 / 10 · 85%", or the scale word. */
    fun markText(g: JSONObject): String {
        g.optString("word").takeIf { it.isNotEmpty() }?.let { return it }
        fun num(x: Double) = "%.2f".format(x).trimEnd('0').trimEnd('.')
        val percent = g.optString("percent").takeIf { it.isNotEmpty() && it != "-" }
        return "${num(g.getDouble("raw"))} / ${num(g.optDouble("max"))}" + (percent?.let { " · $it" } ?: "")
    }

    private fun notifyAttendance(c: Context, sessions: JSONArray) {
        val now = System.currentTimeMillis()
        val notified = SpectrumStore.stringSet(c, "notifiedAttendance")
        for (a in sessions.objects()) {
            val id = a.getString("id")
            if (now < a.getLong("start") || now >= a.getLong("end") || !a.optBoolean("listed", true) || id in notified) continue
            Notify.post(
                c, Notify.ATTENDANCE, "att-$id",
                "Attendance open · ${a.getString("code").ifEmpty { a.getString("course") }}",
                "${a.getString("name")} · until ${SpectrumStore.hm(a.getLong("end"))}. Tap to mark it in Spectrum.",
                Notify.openSpectrum(c, a.getString("url")),
            )
            notified += id
        }
        // Keep only today's sessions so the set doesn't grow.
        val today = sessions.objects().map { it.getString("id") }.toSet()
        SpectrumStore.putStringSet(c, "notifiedAttendance", notified.filter { it in today }.toSet())
    }

    // --- helpers ----------------------------------------------------------------------------

    private fun file(name: String, f: JSONObject) = JSONObject()
        .put("name", name)
        .put("url", f.optString("fileurl"))
        .put("mime", f.optString("mimetype"))
        .put("size", f.optLong("filesize"))

    private val SPACES = Regex("\\s+")
    fun plain(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replace('￼', ' ').replace(SPACES, " ").trim()

    private fun moduleLabel(modname: String) = when (modname) {
        "" -> ""
        "assign" -> "Assignment"
        "quiz" -> "Quiz"
        "forum" -> "Forum"
        "resource" -> "File"
        "folder" -> "Folder"
        "url" -> "Link"
        "page" -> "Page"
        "lti" -> "External tool"
        "h5pactivity" -> "H5P"
        "turnitintooltwo" -> "Turnitin"
        "attendance" -> "Attendance"
        else -> modname.replaceFirstChar { it.uppercase() }
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

internal fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Reminders 24 h and 2 h before each deadline, driven by one exact alarm at a time. */
object Reminders {
    private val OFFSETS = listOf(24 * 3_600_000L to "24 h", 2 * 3_600_000L to "2 h")

    private fun upcoming(c: Context) = SpectrumStore.array(c, "due").objects().filter { !it.optBoolean("overdue") }

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = SpectrumAlarmReceiver.intent(c, SpectrumAlarmReceiver.ACTION_REMINDER)
        val now = System.currentTimeMillis()
        val fired = SpectrumStore.stringSet(c, "reminded")
        val next = if (!SpectrumStore.signedIn(c)) null else upcoming(c)
            .flatMap { d -> OFFSETS.map { (off, _) -> d.getLong("due") - off to "${d.getInt("id")}:$off" } }
            .filter { (at, key) -> at > now && key !in fired }
            .minOfOrNull { it.first }
        if (next == null) am.cancel(pi) else exactAlarm(am, next, pi)
    }

    fun fire(c: Context) {
        val now = System.currentTimeMillis()
        val fired = SpectrumStore.stringSet(c, "reminded")
        val items = upcoming(c)
        for (d in items) {
            val due = d.getLong("due")
            if (due <= now) continue
            // If the phone slept past both reminders, send only the closer one.
            val passed = OFFSETS.filter { (off, _) -> due - off <= now + 60_000 && "${d.getInt("id")}:$off" !in fired }
            val latest = passed.minByOrNull { it.first } ?: continue
            Notify.post(
                c, Notify.DUE, "rem-${d.getInt("id")}",
                "Due in ${latest.second} · ${d.getString("code").ifEmpty { d.getString("course") }}",
                "${d.getString("name")} · ${SpectrumStore.dayWord(due, now)} ${SpectrumStore.hm(due)}",
                Notify.openApp(c, "due"),
            )
            passed.forEach { (off, _) -> fired += "${d.getInt("id")}:$off" }
        }
        val ids = items.map { it.getInt("id").toString() }.toSet()
        SpectrumStore.putStringSet(c, "reminded", fired.filter { it.substringBefore(':') in ids }.toSet())
        schedule(c)
    }
}

/**
 * Attendance sessions are often open for only a few minutes, so during timetabled
 * classes the app checks Spectrum every 10 minutes, and again at each class start
 * and at each session start it already knows about.
 */
object AttendanceWatch {
    fun schedule(c: Context) {
        AutoAttendance.schedule(c) // its own alarm, at each class start and its tries, when it's on
        ClassReminders.schedule(c) // also by the timetable, and wherever it changes: its own alarm, when chosen
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = SpectrumAlarmReceiver.intent(c, SpectrumAlarmReceiver.ACTION_ATTENDANCE)
        if (!SpectrumStore.signedIn(c)) {
            am.cancel(pi)
            return
        }
        val now = System.currentTimeMillis()
        val candidates = mutableListOf<Long>()
        val timetable = Timetable.load(c)
        for (offset in 0..7) {
            val day = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_MONTH, offset) }
            val dayStart = startOfDay(day.timeInMillis)
            for (s in timetable.attendanceDay(day.get(Calendar.DAY_OF_WEEK) - 1)) {
                val start = dayStart + s.start * 60_000L
                val end = dayStart + s.end * 60_000L
                if (now in start until end) candidates += minOf(now + 10 * 60_000L, end)
                else if (start > now) candidates += start + 60_000L
            }
            if (candidates.isNotEmpty()) break
        }
        for (a in SpectrumStore.array(c, "attendance").objects()) {
            val start = a.getLong("start")
            if (start > now && a.optBoolean("listed", true)) candidates += start + 30_000L
        }
        val next = candidates.minOrNull()
        if (next == null) am.cancel(pi) else exactAlarm(am, next, pi)
    }
}

internal fun exactAlarm(am: AlarmManager, at: Long, pi: PendingIntent) {
    if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
}

object Notify {
    const val ATTENDANCE = "attendance"
    const val NEWS = "announcements"
    const val DUE = "deadlines"
    const val GRADES = "grades"
    const val PLANS = "plans"
    const val MARKING = "marking"
    const val CLASSES = "classes"

    private fun manager(c: Context) = c.getSystemService(NotificationManager::class.java)

    /** Android 12 has no notification permission (the check always says no there), only the app's switch. */
    fun allowed(c: Context) =
        (Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager(c).areNotificationsEnabled()

    fun channels(c: Context) = manager(c).createNotificationChannels(
        listOf(
            NotificationChannel(ATTENDANCE, "Attendance", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "When an attendance session is open for one of your classes" },
            NotificationChannel(NEWS, "Announcements", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "New announcements from your Spectrum courses" },
            NotificationChannel(DUE, "Deadlines", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "New assignments and reminders before they're due" },
            NotificationChannel(GRADES, "Marks", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "When a lecturer releases a mark on Spectrum" },
            NotificationChannel(PLANS, "Plans", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Reminders you set for your plans on the Week page" },
            NotificationChannel(CLASSES, "Classes", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Reminders before each of your classes, when you turn them on in Settings" },
            NotificationChannel(MARKING, "Marking attendance", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Shown while the app marks your attendance on Spectrum, for a few seconds" },
        ),
    )

    fun post(c: Context, channel: String, key: String, title: String, text: String, open: PendingIntent) {
        channels(c)
        if (!allowed(c)) return
        val n = Notification.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(Scheme.load(c).dark.first) // the scheme's bright accent
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager(c).notify(key.hashCode(), n)
    }

    fun openApp(c: Context, view: String): PendingIntent = PendingIntent.getActivity(
        c, "view-$view".hashCode(),
        Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_VIEW, view).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun openSpectrum(c: Context, url: String): PendingIntent = PendingIntent.getActivity(
        c, url.hashCode(),
        Intent(c, SpectrumWebActivity::class.java).putExtra(SpectrumWebActivity.EXTRA_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Full sync every 30 minutes when there's a network. */
class SpectrumSyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            SpectrumSync.run(applicationContext, background = true)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 3101

        fun schedule(c: Context) {
            c.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(JOB_ID, ComponentName(c, SpectrumSyncJob::class.java))
                    .setPeriodic(30 * 60_000L, 10 * 60_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }

        /** Schedules the job if it isn't already (a force stop or battery saver can clear it). */
        fun ensure(c: Context) {
            if (SpectrumStore.signedIn(c) && c.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) == null) schedule(c)
        }

        fun cancel(c: Context) = c.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}

/** Attendance checks, deadline, plan and class reminders, and re-arming them after a reboot or update. */
class SpectrumAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.applicationContext
        val pending = goAsync()
        thread {
            try {
                when (intent.action) {
                    ACTION_ATTENDANCE -> SpectrumSync.run(c, attendanceOnly = true, background = true)
                    ACTION_REMINDER -> Reminders.fire(c)
                    ACTION_PLAN -> PlanReminders.fire(c)
                    ACTION_CLASS -> ClassReminders.fire(c)
                    ACTION_AUTOMARK -> AutoAttendance.fire(c, intent.getStringExtra(AutoAttendanceService.EXTRA_DRY))
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        Reminders.schedule(c)
                        AttendanceWatch.schedule(c)
                        PlanReminders.schedule(c)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ATTENDANCE = "com.skylightlim.umtimetable.ATTENDANCE_CHECK"
        const val ACTION_REMINDER = "com.skylightlim.umtimetable.DEADLINE_REMINDER"
        const val ACTION_PLAN = "com.skylightlim.umtimetable.PLAN_REMINDER"
        const val ACTION_AUTOMARK = "com.skylightlim.umtimetable.AUTO_ATTENDANCE"
        const val ACTION_CLASS = "com.skylightlim.umtimetable.CLASS_REMINDER"

        fun intent(c: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            c, action.hashCode(),
            Intent(c, SpectrumAlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
