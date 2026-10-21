package com.skylightlim.umtimetable

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug only: fetches one course's raw core_course_get_contents reply into the app's
 * private storage, so its shape can be inspected with `adb shell run-as`.
 *   adb shell am broadcast -n com.skylightlim.umtimetable/.DebugSpectrumReceiver --ei course 141 [--ez offline true]
 *   adb shell am broadcast -n com.skylightlim.umtimetable/.DebugSpectrumReceiver --es timetable clear|restore|apply
 *   adb shell am broadcast -n com.skylightlim.umtimetable/.DebugSpectrumReceiver --es names fill
 *   adb shell am broadcast -n com.skylightlim.umtimetable/.DebugSpectrumReceiver --es call core_course_get_contents --es params '{"courseid":151}'
 *   adb shell am broadcast -n com.skylightlim.umtimetable/.DebugSpectrumReceiver --es attendance check|sync
 *   adb shell am broadcast -n com.skylightlim.umtimetable/.DebugSpectrumReceiver --es sso probe [--es site timeedit] [--ez signout true]
 *   adb shell am broadcast -n com.skylightlim.umtimetable/.DebugSpectrumReceiver --es timeedit build
 *   adb shell am broadcast -n com.skylightlim.umtimetable/.DebugSpectrumReceiver --es automark dry [--ei cmid N]
 *   adb shell am broadcast -n com.skylightlim.umtimetable/.DebugSpectrumReceiver --es automark alarm [--ei cmid N]
 *   adb shell am broadcast -n com.skylightlim.umtimetable/.DebugSpectrumReceiver --es sync full
 *   adb shell am broadcast -n com.skylightlim.umtimetable/.DebugSpectrumReceiver --es haptic tick|back|cross|uncross
 *   adb shell am broadcast -n com.skylightlim.umtimetable/.DebugSpectrumReceiver --es fake signin
 *   adb shell am broadcast -n com.skylightlim.umtimetable/.DebugSpectrumReceiver --es mayaSite http://localhost:8765/
 */
class DebugSpectrumReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.applicationContext
        // --es timetable clear|restore: an empty timetable, or none at all (as after clearing the app's data).
        // --es timetable apply: use files/timetable-pending.json (pushed with adb) as the app's timetable.
        // --es names fill: what each sync does with the cached course list (MayaImport.fillNames).
        if (intent.getStringExtra("names") == "fill") {
            MayaImport.fillNames(c, SpectrumStore.array(c, "courses"))
            TimetableWidget.updateAll(c)
            Log.i("UMTimetable", "debug: names ${Timetable.load(c).courses.joinToString { "${it.code}=${it.name}" }}")
            return
        }
        // --es fake signin: a made-up Spectrum sign-in and a saved file, to try Sign out without a real
        // account (keep the network off: Spectrum would refuse the token and sign the app out).
        if (intent.getStringExtra("fake") == "signin") {
            SecureStore.put(c, "token", "debug-fake-token")
            SpectrumStore.write(c, "debug-fake", "{}")
            Log.i("UMTimetable", "debug: fake sign-in, signedIn=${SpectrumStore.signedIn(c)}")
            return
        }
        intent.getStringExtra("timetable")?.let { mode ->
            val pending = File(c.filesDir, "timetable-pending.json")
            Timetable.setLocal(
                c,
                when (mode) {
                    "clear" -> """{"courses": {}, "sessions": []}"""
                    "apply" -> pending.readText().also { org.json.JSONObject(it); pending.delete() }
                    else -> null
                },
            )
            TimetableWidget.updateAll(c)
            AttendanceWatch.schedule(c)
            Log.i("UMTimetable", "debug: timetable $mode -> ${Timetable.load(c).sessions.size} classes")
            return
        }
        // --es maya import: run the importer on no_backup/maya/page.json (a MAYA page pushed with adb,
        // as {"html": "…"}) and use the result. The app itself never saves MAYA pages.
        // --es mayaSite http://localhost:8765/: the MAYA import against a stand-in MAYA served from the
        // computer (adb reverse tcp:8765 tcp:8765), to test the steps it takes by itself after sign-in.
        // Only works with the app in front: Android won't open a screen for an app in the background.
        intent.getStringExtra("mayaSite")?.let { site ->
            c.startActivity(Intent(c, MayaActivity::class.java).putExtra(MayaActivity.EXTRA_SITE, site).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (intent.getStringExtra("maya") == "import") {
            val latest = File(c.noBackupFilesDir, "maya/page.json").takeIf { it.exists() }
            val found = latest?.let { MayaImport.timetable(c, MayaImport.events(org.json.JSONObject(it.readText()).optString("html"))) }
            if (found != null) {
                Timetable.setLocal(c, found.json.toString())
                TimetableWidget.updateAll(c)
                AttendanceWatch.schedule(c)
            }
            Log.i("UMTimetable", "debug: MAYA import from ${latest?.name}: ${found?.classes ?: 0} classes, ${found?.courses ?: 0} courses, ${found?.weeks ?: 0} weeks, ${found?.lecturersKept ?: 0} lecturers kept")
            return
        }
        // --es call <function> --es params '<json>': any web service, as the user; the raw reply goes to
        // no_backup/spectrum/raw-call.json for inspection with run-as. Delete it afterwards.
        // --es attendance check: every course's attendance activities, whose AttendanceMatch thinks
        // each one is, and which it picks for each of the user's classes. Logged, nothing saved.
        // --es haptic tick|back|cross|uncross: one vibration at the chosen strength (Haptics). Android
        // ignores it while the app is in the background, but `dumpsys vibrator_manager` still lists it.
        intent.getStringExtra("haptic")?.let { kind ->
            val v = android.view.View(c)
            when (kind) {
                "back" -> Haptics.back(v)
                "cross" -> Haptics.threshold(v, true)
                "uncross" -> Haptics.threshold(v, false)
                else -> Haptics.tick(v)
            }
            Log.i("UMTimetable", "debug: haptic $kind at ${Haptics.level(c)}")
            return
        }
        // --es automark dry [--ei cmid N]: what auto attendance would find on an attendance activity's page
        // (today's first session's, or activity N's), without marking anything: logged as "automark dry".
        // --es automark alarm [--ei cmid N]: the same dry read, the way the real one runs: an exact alarm in
        // 15 s starts the foreground service (so it's tried with the app in the background). Logged to AttendanceLog.
        if (intent.getStringExtra("automark") == "alarm") {
            val cmid = intent.getIntExtra("cmid", 0)
            val url = if (cmid > 0) "${Spectrum.SITE}/mod/attendance/view.php?id=$cmid"
            else SpectrumStore.array(c, "attendance").objects().firstOrNull()?.optString("url") ?: return
            val pi = android.app.PendingIntent.getBroadcast(
                c, 7_001,
                Intent(c, SpectrumAlarmReceiver::class.java).setAction(SpectrumAlarmReceiver.ACTION_AUTOMARK).putExtra(AutoAttendanceService.EXTRA_DRY, url),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            exactAlarm(c.getSystemService(android.app.AlarmManager::class.java), System.currentTimeMillis() + 15_000, pi)
            Log.i("UMTimetable", "automark alarm: dry read of $url in 15 s")
            return
        }
        if (intent.getStringExtra("automark") == "dry") {
            val pending = goAsync()
            val cmid = intent.getIntExtra("cmid", 0)
            Thread {
                try {
                    val url = if (cmid > 0) "${Spectrum.SITE}/mod/attendance/view.php?id=$cmid"
                    else SpectrumStore.array(c, "attendance").objects().firstOrNull()?.optString("url")
                    Log.i("UMTimetable", "automark dry: ${url?.let { AutoAttendance.mark(c, it, dry = true) } ?: "no attendance today; pass --ei cmid"}")
                } finally {
                    pending.finish()
                }
            }.start()
            return
        }
        // --es timeedit build: the timetable TimeEdit would give, compared with the app's (DebugTimeEdit).
        if (intent.getStringExtra("timeedit") == "build") {
            val pending = goAsync()
            DebugTimeEdit.build(c) { pending.finish() }
            return
        }
        // --es sso probe: whether MAYA and TimeEdit let the app in with its SiswaMail session (SsoProbe).
        if (intent.getStringExtra("sso") == "probe") {
            val pending = goAsync()
            SsoProbe.run(c, intent.getStringExtra("site"), intent.getBooleanExtra("signout", false)) { pending.finish() }
            return
        }
        // --es attendance sync: the background attendance check (SpectrumSync.run, attendanceOnly).
        // --es sync full: the full sync the app runs when it opens.
        if (intent.getStringExtra("attendance") == "sync" || intent.getStringExtra("sync") == "full") {
            val full = intent.getStringExtra("sync") == "full"
            val pending = goAsync()
            thread {
                try {
                    Log.i("UMTimetable", "debug: ${if (full) "full" else "attendance"} sync -> ${SpectrumSync.run(c, attendanceOnly = !full) ?: "ok"}")
                } finally {
                    pending.finish()
                }
            }
            return
        }
        if (intent.getStringExtra("attendance") == "check") {
            val pending = goAsync()
            thread {
                try {
                    val token = SpectrumStore.token(c) ?: error("not signed in")
                    val timetable = Timetable.load(c)
                    for (course in SpectrumStore.array(c, "courses").objects()) {
                        val code = course.getString("code")
                        val sections = Spectrum.call(token, "core_course_get_contents", mapOf("courseid" to course.getInt("id"), "options" to listOf(mapOf("name" to "modname", "value" to "attendance"), mapOf("name" to "excludecontents", "value" to true)))) as org.json.JSONArray
                        val modules = sections.objects().flatMap { (it.optJSONArray("modules") ?: org.json.JSONArray()).objects() }.filter { it.optString("modname") == "attendance" }
                        val list = modules.filter { it.optBoolean("uservisible", true) }.map { AttendanceMatch.Activity(it.getInt("id"), it.getInt("instance"), SpectrumSync.plain(it.optString("name"))) }
                        val classes = timetable.sessions.filter { it.course.code == code }
                        val occ = classes.firstOrNull()?.course?.occ ?: 0
                        Log.i("UMTimetable", "check $code occ $occ: ${modules.size} attendance activities, ${list.size} visible")
                        for (a in list) Log.i("UMTimetable", "check $code   ${AttendanceMatch.owner(a, list, occ, classes)}  ${a.name}")
                        for (s in classes) Log.i("UMTimetable", "check $code   day ${s.day} ${s.type} ${s.room} -> ${AttendanceMatch.pick(list, occ, s)?.name}")
                    }
                } catch (e: Exception) {
                    Log.w("UMTimetable", "check failed: ${e.message}")
                } finally {
                    pending.finish()
                }
            }
            return
        }
        intent.getStringExtra("call")?.let { fn ->
            fun plain(v: Any?): Any? = when (v) {
                is org.json.JSONObject -> v.keys().asSequence().associateWith { plain(v.get(it)) }
                is org.json.JSONArray -> (0 until v.length()).map { plain(v.get(it)) }
                org.json.JSONObject.NULL -> null
                else -> v
            }
            @Suppress("UNCHECKED_CAST")
            val params = plain(org.json.JSONObject(intent.getStringExtra("params") ?: "{}")) as Map<String, Any?>
            val pending = goAsync()
            thread {
                try {
                    val reply = Spectrum.call(SpectrumStore.token(c) ?: error("not signed in"), fn, params).toString()
                    File(c.noBackupFilesDir, "spectrum/raw-call.json").writeText(reply)
                    Log.i("UMTimetable", "debug: $fn ${reply.length} chars")
                } catch (e: Exception) {
                    Log.w("UMTimetable", "debug: $fn failed: ${e.message}")
                } finally {
                    pending.finish()
                }
            }
            return
        }
        val course = intent.getIntExtra("course", 0)
        val pending = goAsync()
        thread {
            try {
                val rawFile = File(c.noBackupFilesDir, "spectrum/raw-contents-$course.json")
                // --ez offline true re-formats the last raw reply without touching the network.
                val raw = if (intent.getBooleanExtra("offline", false)) rawFile.readText() else {
                    val token = SpectrumStore.token(c) ?: error("not signed in")
                    Spectrum.call(token, "core_course_get_contents", mapOf("courseid" to course)).toString().also { rawFile.writeText(it) }
                }
                SpectrumStore.write(c, "contents-$course", SpectrumSync.normalizeContents(org.json.JSONArray(raw)).toString())
                Log.i("UMTimetable", "debug: course $course raw contents ${raw.length} chars")
            } catch (e: Exception) {
                Log.w("UMTimetable", "debug: course $course failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
