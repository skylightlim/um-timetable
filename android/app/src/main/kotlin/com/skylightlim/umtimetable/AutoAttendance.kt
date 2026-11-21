package com.skylightlim.umtimetable

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Settings' "Mark my attendance": as each class starts, the app marks the user present in its
 * attendance session on Spectrum, as the session page's own "Submit attendance" link would. Spectrum has
 * no web service for it, so a hidden page opens the class's attendance activity with the user's
 * Spectrum sign-in (Microsoft signs it in again when Spectrum's session has run out) and runs
 * assets/attendance-mark.js there. Marked or not, a notification says so, and what stopped it:
 * a session not open yet, a lecturer's password, a QR code, the campus network only. When that first try
 * doesn't mark it, the pictures on the activity's page and the session's are read for the session's QR
 * code ([AttendanceCode], [pageCode]): a lecturer who put it there has put its password in it, and the
 * code's link marks the user with no one touching the phone.
 *
 * The alarm starts a short foreground service ([AutoAttendanceService]) for it: Android lets an app's
 * foreground service use mobile data even when the user has turned the app's background data off, and
 * an exact alarm may start one while the app is in the background. Every step goes to [AttendanceLog].
 *
 * No session open yet (or Spectrum out of reach): it tries again at [TRIES] minutes into the class, and
 * says so only after the last try. What the user must do something about (a password, a QR code, the
 * campus network, signing in again) is said at once. Only for classes whose course has an attendance
 * activity matched to them ([AttendanceMatch]); a class without one has nothing to mark and says
 * nothing. Public holidays and weeks without classes (break, revision, exams) are skipped.
 *
 * Some phones (Xiaomi, OPPO, vivo, Huawei with Autostart off; Samsung's Sleeping apps) don't let an alarm
 * wake an app that isn't running. An alarm that never came is logged ("missed: the 08:02 alarm never
 * came") when the next [schedule] finds it past, so the Classes tab can say why, and the 30-minute
 * background sync makes up for it ([catchUp]) while the class is still on.
 */
object AutoAttendance {
    private const val FLAG = "autoAttendance"
    private const val SCRIPT = "attendance-mark.js"
    /** The tries, in minutes into a class: at its start, then while no session is open, until it ends. */
    private val TRIES = listOf(0, 2, 5, 10, 15, 20, 30, 45) + (75..600 step 30)
    /** Answers no later try can change: the user has to act. */
    private val FINAL = setOf("password", "qr", "network", "nostatus", "signedout", "blocked")

    fun enabled(c: Context) = SpectrumStore.flag(c, FLAG)

    fun setEnabled(c: Context, on: Boolean) {
        SpectrumStore.setFlag(c, FLAG, on)
        AttendanceLog.add(c, "turned ${if (on) "on" else "off"}")
        schedule(c)
    }

    /**
     * The classes to mark on [date]: none on a holiday or in a week without classes. A course whose
     * occurrence the user changed is marked at its Spectrum occurrence's classes ([Timetable.attendance]).
     */
    private fun classes(c: Context, date: LocalDate): List<Session> =
        if (Holidays.on(date) != null || weekOff(date) != null) emptyList() else Timetable.load(c).attendanceDay(date.dayOfWeek.value % 7)

    /** The tries of class [s] on the day starting at [dayStart], as times, all before it ends. */
    private fun tries(dayStart: Long, s: Session) = TRIES.filter { s.start + it < s.end }.map { dayStart + (s.start + it) * 60_000L }

    /**
     * An exact alarm at the next try: today's next for a class not done yet, else the next class's start
     * (in the coming week). None when it's off.
     */
    @Synchronized
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = SpectrumAlarmReceiver.intent(c, SpectrumAlarmReceiver.ACTION_AUTOMARK)
        if (!enabled(c) || !SpectrumStore.signedIn(c)) {
            SpectrumStore.putLong(c, NEXT, 0)
            return am.cancel(pi)
        }
        val now = System.currentTimeMillis()
        // The alarm set last: [fire] clears it when it comes, so one still here and past hasn't come.
        val due = SpectrumStore.long(c, NEXT)
        if (due in 1..now) {
            // Just past: Android may hand it over a little late. Left as it is rather than replaced by a later try.
            if (now - due < MISSED_AFTER) return exactAlarm(am, due, pi)
            SpectrumStore.putLong(c, NEXT, 0)
            if (now - due < 12 * 3_600_000L) missed(c, due, now)
        }
        val done = SpectrumStore.stringSet(c, DONE)
        for (offset in 0..7L) {
            val date = LocalDate.now().plusDays(offset)
            val dayStart = startOfDay(date)
            val next = classes(c, date).filter { key(date, it) !in done }
                .flatMap { tries(dayStart, it) }.filter { it > now }.minOrNull()
            if (next != null) {
                if (next != SpectrumStore.long(c, NEXT)) AttendanceLog.add(c, "next try ${LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), java.time.ZoneId.systemDefault()).format(STAMP)}${if (am.canScheduleExactAlarms()) "" else " (inexact: Android may run it late)"}")
                SpectrumStore.putLong(c, NEXT, next)
                return exactAlarm(am, next, pi)
            }
        }
        SpectrumStore.putLong(c, NEXT, 0)
        am.cancel(pi)
    }

    private const val NEXT = "autoAttendanceNext"
    private const val MISSED = "autoAttendanceMissed"
    /** How long past its time an alarm that hasn't come is taken as never coming. */
    private const val MISSED_AFTER = 60_000L

    /**
     * The alarm at [due] never came: logged with what stood in the way ([AttendanceHistory] reads it back),
     * and kept for the background sync to make up for ([catchUp]). A phone switched off or restarting then
     * (it started since) is said as such: no battery setting is to blame.
     */
    private fun missed(c: Context, due: Long, now: Long) {
        val restarted = now - SystemClock.elapsedRealtime() > due
        AttendanceLog.add(c, "missed: the ${SpectrumStore.hm(due)} alarm never came${if (restarted) " $RESTARTED" else ""}; ${conditions(c)}")
        SpectrumStore.putLong(c, MISSED, due)
        if (!restarted) SpectrumStore.putLong(c, MISSED_AT, now)
    }

    private const val MISSED_AT = "autoAttendanceMissedAt"

    /** When an alarm was last found never to have come with the phone on (0: never), for [BatteryHelp]. */
    fun lastMissed(c: Context) = SpectrumStore.long(c, MISSED_AT)

    /** What [missed] adds when the phone started after the alarm was due. */
    const val RESTARTED = "(the phone was off or restarting then)"

    /**
     * The 30-minute background sync's catch-up: when an alarm never came ([missed]), the classes in progress
     * are marked now rather than waiting for an alarm that may not come either. Runs in the sync job's
     * thread, which has a network; [run] does nothing when no class is on or one is being marked already.
     */
    fun catchUp(c: Context) {
        val due = SpectrumStore.long(c, MISSED)
        if (due == 0L) return
        SpectrumStore.putLong(c, MISSED, 0)
        if (!enabled(c) || !SpectrumStore.signedIn(c)) return
        AttendanceLog.add(c, "catching up from the background sync, for the ${SpectrumStore.hm(due)} alarm")
        run(c)
    }
    private val STAMP = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

    /**
     * The alarm: the marking runs in a short foreground service (a hidden page can take longer than an
     * alarm may, and only a foreground service gets mobile data with background data off), then the next
     * alarm is set. [dry] (debug) is a page to only read, through the same service.
     */
    fun fire(c: Context, dry: String? = null) {
        val late = (System.currentTimeMillis() - SpectrumStore.long(c, NEXT)) / 1000
        if (dry == null) {
            SpectrumStore.putLong(c, NEXT, 0) // it came: [schedule] doesn't take it as missed
            AttendanceLog.add(c, "alarm${if (late in 0..86_400) ", ${late}s after it was due" else ""}")
        }
        try {
            c.startForegroundService(Intent(c, AutoAttendanceService::class.java).putExtra(AutoAttendanceService.EXTRA_DRY, dry))
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException: Android didn't allow it now. A job still runs, on Wi-Fi at least.
            AttendanceLog.add(c, "no foreground service (${e.javaClass.simpleName}: ${e.message}), running as a job instead")
            if (dry == null) c.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(JOB_ID, ComponentName(c, AutoAttendanceJob::class.java))
                    .setExpedited(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build(),
            )
        }
        if (dry == null) schedule(c)
    }

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Marks every class in progress (started, or starting within the minute) that isn't done: Spectrum's
     * attendance for today is read again first, so a session made since the last try counts.
     */
    fun run(c: Context) {
        if (!enabled(c) || !SpectrumStore.signedIn(c) || !running.compareAndSet(false, true)) return
        try {
            markDue(c)
        } finally {
            running.set(false)
            schedule(c)
        }
    }

    private fun markDue(c: Context) {
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val dayStart = startOfDay(today)
        val done = SpectrumStore.stringSet(c, DONE).filter { it.startsWith("$today ") }.toMutableSet()
        val due = classes(c, today).filter { s ->
            now >= dayStart + s.start * 60_000L - 60_000 && now < dayStart + s.end * 60_000L && key(today, s) !in done
        }
        AttendanceLog.add(c, "run: ${due.size} class(es) to mark; ${conditions(c)}")
        if (due.isEmpty()) return
        val error = SpectrumSync.run(c, attendanceOnly = true, background = true)
        val sessions = SpectrumStore.array(c, "attendance").objects()
        AttendanceLog.add(c, "attendance read${error?.let { " failed: $it (using the last one)" } ?: ""}: " +
            sessions.joinToString("; ") { "${it.optString("code")} \"${it.optString("name")}\" ${SpectrumStore.hm(it.getLong("start"))}-${SpectrumStore.hm(it.getLong("end"))}${if (it.optBoolean("listed", true)) "" else " (not in the calendar)"}" }.ifEmpty { "none today" })
        for (s in due) {
            val what = AttendanceHistory.what(s) // as the log page reads it back
            val from = dayStart + s.start * 60_000L
            val to = dayStart + s.end * 60_000L
            // The try after this one, if the class lasts that long.
            val later = tries(dayStart, s).firstOrNull { it > System.currentTimeMillis() + 30_000 }
            fun finish() {
                done += key(today, s)
                SpectrumStore.putStringSet(c, DONE, done)
            }
            val session = sessions.firstOrNull { it.optString("code") == s.course.code && it.getLong("start") < to && it.getLong("end") > from }
            if (session == null) {
                // Its session may still be put in the calendar; after 20 minutes it's taken that there's none.
                val giveUp = later == null || later - from > 20 * 60_000L
                AttendanceLog.add(c, "$what: no attendance activity for this class${if (giveUp) ", last try" else ""}. ${candidates(c, s)}")
                if (giveUp) finish()
                continue
            }
            val url = session.getString("url")
            AttendanceLog.add(c, "$what: opening \"${session.optString("name")}\" ($url)")
            val answer = mark(c, url, dry = false)
            val last = answer.optBoolean("ok") || answer.optString("error") in FINAL || later == null
            AttendanceLog.add(c, "$what: $answer${if (last) "" else "; trying again at ${SpectrumStore.hm(later)}"}")
            if (!last) continue
            finish()
            // Marked already (by the user, or the lecturer): nothing to tell.
            if (!answer.optBoolean("already")) notify(c, s, session.getString("id"), url, answer)
        }
    }

    private const val DONE = "autoAttendanceDone"
    private fun key(day: LocalDate, s: Session) = "$day ${s.start} ${s.course.code}"

    /** For the log: the course's attendance activities, each with how well it fits [s] (x: it can't be). */
    private fun candidates(c: Context, s: Session): String {
        val courseId = SpectrumStore.array(c, "courses").objects().firstOrNull { it.optString("code") == s.course.code }?.optInt("id") ?: return "Not a Spectrum course."
        val list = SpectrumStore.json(c, "attendance-activities").optJSONArray("$courseId")?.objects().orEmpty()
        if (list.isEmpty()) return "The course has no attendance activities."
        return "Occ ${s.course.occ}; its activities: " + list.joinToString("; ") { "\"${it.optString("name")}\" ${AttendanceMatch.score(it.optString("name"), s.course.occ, s) ?: "x"}" }
    }

    /**
     * What stands in the way of a background run, for the log: the network, whether Android limits this
     * app's mobile data in the background (background data off, Data Saver), exact alarms, notifications,
     * battery limits and the app's standby bucket.
     */
    fun conditions(c: Context): String {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "no network"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "other network"
        }
        val background = when (cm.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "background data limited"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "Data Saver on, app exempt"
            else -> "background data allowed"
        }
        val battery = when {
            c.getSystemService(ActivityManager::class.java).isBackgroundRestricted -> "battery Restricted"
            c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName) -> "battery Unrestricted"
            else -> "battery Optimised"
        }
        val bucket = when (val b = c.getSystemService(UsageStatsManager::class.java).appStandbyBucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
            else -> "bucket $b"
        }
        return listOf(
            "$net${if (cm.isActiveNetworkMetered) " (metered)" else ""}",
            background,
            if (SpectrumSync.networkBlocked(c)) "network blocked now" else null,
            if (c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) null else "no exact alarms",
            if (Notify.allowed(c)) null else "notifications off",
            battery,
            "standby $bucket",
        ).filterNotNull().joinToString(", ")
    }

    /** Says how it went, in the place of the session's "Attendance open" notification. */
    private fun notify(c: Context, s: Session, id: String, url: String, answer: JSONObject) {
        val what = "${s.course.code} ${s.type.lowercase()}"
        if (answer.optBoolean("ok")) {
            val status = answer.optString("status").ifBlank { "present" }.lowercase()
            val how = if (answer.has("first")) ", with the QR code on its page" else ""
            Notify.post(c, Notify.ATTENDANCE, "att-$id", "Marked $status · $what", "Spectrum recorded your attendance at ${SpectrumStore.hm(System.currentTimeMillis())}$how.", Notify.openSpectrum(c, url))
            return
        }
        val why = AttendanceHistory.why(answer.optString("error"))
        if (!Notify.allowed(c)) AttendanceLog.add(c, "notifications are off: the user isn't told")
        Notify.post(c, Notify.ATTENDANCE, "att-$id", "Couldn't mark attendance · $what", "$why Tap to mark it yourself.", Notify.openSpectrum(c, url))
    }

    /**
     * The session's QR code among a page's [images] (the script's pictures: base64, or another site's
     * address), and what was found, for the log: "session 123 from qr.png, with a password", or "2
     * picture(s), no attendance code (qr.png: a code to forms.office.com/r)". Run off the main thread.
     */
    internal fun pageCode(images: JSONArray): Pair<AttendanceCode?, String> {
        val notes = mutableListOf<String>()
        for (image in images.objects()) {
            val src = image.optString("src").ifBlank { "a picture" }
            val bytes = runCatching {
                image.optString("data").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.DEFAULT) } ?: download(image.optString("url"))
            }.getOrNull()
            if (bytes == null) {
                notes += "$src didn't load"
                continue
            }
            val text = readCode(bytes) ?: continue
            val code = AttendanceCode.parse(text)
            if (code != null) return code to "session ${code.sessid} from $src${if (code.qrpass != null) ", with a password" else ""}"
            // Not a session's: where it goes (no query, which may hold someone's password).
            notes += "$src: a code to " + (WHERE.find(text)?.let { it.groupValues[1] + it.groupValues[2] } ?: "text, not a link")
        }
        return null to "${images.length()} picture(s), no attendance code" + (if (notes.isEmpty()) "" else " (${notes.joinToString("; ")})")
    }

    private val WHERE = Regex("^https?://([^/?#]+)([^?#]*)")

    /** Another site's picture, as a browser showing the page would load it: no cookies, at most 4 MB. */
    private fun download(url: String): ByteArray? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        return try {
            if (conn.responseCode != 200 || conn.contentLengthLong > 4_000_000) null
            else conn.inputStream.use { input -> input.readNBytes(4_000_001).takeIf { it.size <= 4_000_000 } }
        } finally {
            conn.disconnect()
        }
    }

    /** A picture's QR code text: the picture at most 2048 px a side (a phone photo is read smaller). */
    private fun readCode(bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            QrDecoder.decode(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Opens [url], an attendance activity's page, in a hidden page and runs the script there: marks the
     * user, or ([dry]) only says what the page offers, and what QR code its pictures hold. Blocks (call it
     * off the main thread) until it has an answer, at most about a minute and a half. Errors of its own:
     * signedout (Microsoft wants the user), blocked (Android cut the app's network), unreachable. The answer
     * lists the pages it went through; marked with the page's QR code, it says so ("qr", and "first": what
     * the first try ran into).
     */
    fun mark(c: Context, url: String, dry: Boolean): JSONObject {
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        var answer = JSONObject().put("error", "unreachable")
        val pages = mutableListOf<String>()
        main.post {
            val web = WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
            var over = false
            var asked = false
            var lastHost = ""
            fun finish(a: JSONObject) {
                if (over) return
                over = true
                answer = a.put("pages", pages.joinToString(" > "))
                web.destroy()
                done.countDown()
            }
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, page: String) {
                    if (over) return
                    val u = Uri.parse(page)
                    lastHost = u.host.orEmpty()
                    pages += "$lastHost${u.path.orEmpty()}"
                    if (asked || lastHost != Uri.parse(Spectrum.SITE).host || u.path != "/mod/attendance/view.php") return
                    asked = true
                    ask(web, dry) { answer ->
                        val images = answer.remove("images") as? JSONArray
                        if (images?.length() == 0 && !answer.optBoolean("ok")) answer.put("qr", "no pictures")
                        if (answer.optBoolean("ok") || images == null || images.length() == 0) return@ask finish(answer)
                        // Not marked: the session's QR code may be among the pages' pictures. Read off the main thread.
                        thread {
                            val (code, found) = runCatching { pageCode(images) }.getOrElse { null to "pictures unreadable: $it" }
                            main.post {
                                answer.put("qr", found)
                                if (code == null || dry || over) return@post finish(answer)
                                // Its link, from this page: "first" says what the first try ran into.
                                ask(web, false, code.link()) { second -> finish(second.put("qr", found).put("first", answer.optString("error"))) }
                            }
                        }
                    }
                }

                // The page itself didn't load (no network, or Android cut this app off): say so now, rather
                // than run the script on the error page and wait for an answer that can't come.
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame || over) return
                    pages += "${request.url.host}${request.url.path.orEmpty()} (${error.description})"
                    finish(JSONObject().put("error", if (SpectrumSync.networkBlocked(c)) "blocked" else "unreachable").put("detail", error.description.toString()))
                }
            }
            web.loadUrl(url)
            // Still on Microsoft's sign-in by then: it wants the user. Not a word at all: Spectrum is out of reach.
            main.postDelayed({
                if (!asked) finish(JSONObject().put("error", if (hostIn(lastHost, "microsoftonline.com", "live.com")) "signedout" else "unreachable"))
            }, 30_000)
            main.postDelayed({ finish(JSONObject().put("error", "unreachable").put("detail", "no answer in time")) }, 90_000)
        }
        done.await(100, TimeUnit.SECONDS)
        return answer
    }

    /**
     * Runs the script in [web]'s page and hands its answer to [done], over a port only Spectrum's page can
     * take. With [link] (a session's link from its QR code), the script opens that instead of the page's own.
     */
    private fun ask(web: WebView, dry: Boolean, link: String? = null, done: (JSONObject) -> Unit) {
        val (mine, theirs) = web.createWebMessageChannel()
        mine.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(port: WebMessagePort, message: WebMessage) {
                mine.close()
                done(runCatching { JSONObject(message.data) }.getOrElse { JSONObject().put("error", "failed").put("detail", "unreadable answer") })
            }
        })
        web.evaluateJavascript(web.context.assets.open(SCRIPT).bufferedReader().use { it.readText() }) {
            web.postWebMessage(WebMessage(JSONObject().put("dry", dry).apply { if (link != null) put("link", link) }.toString(), arrayOf(theirs)), Uri.parse(Spectrum.SITE))
        }
    }

    private const val JOB_ID = 3102
}

/**
 * Auto attendance's record, one line a step, kept in the app's folder of shared storage so that any
 * phone's can be read over USB, release build or not:
 *   adb shell cat /sdcard/Android/data/com.skylightlim.umtimetable/files/attendance-log.txt
 * No sign-in, cookies or passwords go in it: only times, classes, activity names and what Spectrum said.
 */
object AttendanceLog {
    private const val FILE = "attendance-log.txt"
    /** Past this, the older half goes (about a month of school days). */
    private const val MAX = 256 * 1024
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.UK)

    fun file(c: Context): File? = c.getExternalFilesDir(null)?.let { File(it, FILE) }

    @Synchronized
    fun add(c: Context, line: String) {
        Log.i("UMTimetable", "auto attendance: $line")
        val f = file(c) ?: return
        runCatching {
            if (f.length() > MAX) f.writeText(f.readText().let { it.substring(it.length / 2).substringAfter('\n') })
            f.appendText("${STAMP.format(LocalDateTime.now())} $line\n")
        }
    }

    /** One step of the log: when ("2026-10-27 08:01:05") and what happened; [problem] when it went wrong. */
    data class Entry(val time: LocalDateTime, val text: String) {
        val problem get() = isProblem(text)
    }

    /** The log as it is now, oldest first; empty before anything is logged. */
    fun read(c: Context): List<Entry> = runCatching {
        val f = file(c)?.takeIf { it.exists() } ?: return emptyList()
        val stamp = f.lastModified() to f.length()
        parsed?.takeIf { it.first == stamp }?.second ?: parse(f.readText()).also { parsed = stamp to it }
    }.getOrNull().orEmpty()

    /** The file as last parsed, until it changes: Settings reads it on every redraw, and a full log takes about 20 ms to parse. */
    @Volatile private var parsed: Pair<Pair<Long, Long>, List<Entry>>? = null

    /** The file's text as entries: a line that doesn't start with a time carries on the one before it. */
    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        for (line in text.lineSequence()) {
            val m = LINE.matchEntire(line)
            val time = m?.let { runCatching { LocalDateTime.parse(it.groupValues[1], STAMP) }.getOrNull() }
            if (m != null && time != null) out += Entry(time, m.groupValues[2])
            else if (line.isNotBlank() && out.isNotEmpty()) out[out.size - 1] = out.last().let { it.copy(text = it.text + "\n" + line) }
        }
        return out
    }

    /**
     * Whether a step went wrong: a read or a run that failed, Spectrum's answer naming an error, no
     * attendance activity for a class, the run cut short, nobody told because notifications are off, or
     * an alarm that never came.
     */
    fun isProblem(text: String) = PROBLEM.containsMatchIn(text)

    private val LINE = Regex("""(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) (.*)""")
    private val PROBLEM = Regex("""\bfailed\b|"error"\s*:|ran out of time|no attendance activity|notifications are off|alarm never came""")
}

/**
 * Auto attendance's run, as a short foreground service with a quiet "Marking your attendance"
 * notification while it lasts (a minute at most): see [AutoAttendance] for why not a job.
 */
class AutoAttendanceService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notify.channels(this)
        val n = Notification.Builder(this, Notify.MARKING)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(Scheme.load(this).dark.first)
            .setContentTitle("Marking your attendance")
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE) else startForeground(ID, n)
        val dry = intent?.getStringExtra(EXTRA_DRY)
        val c = applicationContext
        thread {
            try {
                if (dry != null) AttendanceLog.add(c, "dry run: ${AutoAttendance.conditions(c)}; ${AutoAttendance.mark(c, dry, dry = true)}")
                else AutoAttendance.run(c)
            } catch (e: Exception) {
                AttendanceLog.add(c, "run failed: $e")
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    /** A short service has about three minutes; the run never takes that long, but if it does, it ends here. */
    override fun onTimeout(startId: Int) {
        AttendanceLog.add(applicationContext, "ran out of time")
        stopSelf()
    }

    companion object {
        const val EXTRA_DRY = "dry"
        private const val ID = 3103
    }
}

/** The marking, when Android doesn't allow a foreground service at that moment. */
class AutoAttendanceJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            try {
                AutoAttendance.run(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}
