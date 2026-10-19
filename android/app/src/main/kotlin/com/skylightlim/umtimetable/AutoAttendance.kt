package com.skylightlim.umtimetable

import android.app.AlarmManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.time.LocalDate
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Settings' "Mark my attendance": five minutes into each class, the app marks the user present in its
 * attendance session on Spectrum, as the session page's own "Submit attendance" link would. Spectrum has
 * no web service for it, so a hidden page opens the class's attendance activity with the user's
 * Spectrum sign-in (Microsoft signs it in again when Spectrum's session has run out) and runs
 * assets/attendance-mark.js there. Marked or not, a notification says so, and what stopped it:
 * a session not open yet, a lecturer's password, a QR code, the campus network only.
 *
 * Only for classes whose course has an attendance activity matched to them ([AttendanceMatch]); a
 * class without one has nothing to mark and says nothing. One try per class.
 */
object AutoAttendance {
    private const val TAG = "UMTimetable"
    private const val FLAG = "autoAttendance"
    private const val SCRIPT = "attendance-mark.js"
    /** How long into a class it marks. */
    const val AFTER_MIN = 5

    fun enabled(c: Context) = SpectrumStore.flag(c, FLAG)

    fun setEnabled(c: Context, on: Boolean) {
        SpectrumStore.setFlag(c, FLAG, on)
        schedule(c)
    }

    /** An exact alarm five minutes into the next class (today or the coming week), or none when it's off. */
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = SpectrumAlarmReceiver.intent(c, SpectrumAlarmReceiver.ACTION_AUTOMARK)
        if (!enabled(c) || !SpectrumStore.signedIn(c)) return am.cancel(pi)
        val now = System.currentTimeMillis()
        val timetable = Timetable.load(c)
        for (offset in 0..7) {
            val day = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_MONTH, offset) }
            val dayStart = startOfDay(day.timeInMillis)
            val next = timetable.day(day.get(Calendar.DAY_OF_WEEK) - 1)
                .map { dayStart + (it.start + AFTER_MIN) * 60_000L }.filter { it > now }.minOrNull()
            if (next != null) return exactAlarm(am, next, pi)
        }
        am.cancel(pi)
    }

    /** The alarm: the marking runs as a job (a hidden page can take longer than an alarm may), then the next alarm is set. */
    fun fire(c: Context) {
        c.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(JOB_ID, ComponentName(c, AutoAttendanceJob::class.java))
                .setExpedited(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build(),
        )
        schedule(c)
    }

    /**
     * Marks every class in progress that's five minutes in and hasn't been tried: Spectrum's attendance
     * for today is read again first, so a session made since the last sync counts.
     */
    fun run(c: Context) {
        if (!enabled(c) || !SpectrumStore.signedIn(c)) return
        val now = System.currentTimeMillis()
        val dayStart = startOfDay(now)
        val today = LocalDate.now()
        val tried = SpectrumStore.stringSet(c, TRIED).filter { it.startsWith("$today ") }.toMutableSet()
        val due = Timetable.load(c).day(today.dayOfWeek.value % 7).filter { s ->
            val start = dayStart + s.start * 60_000L
            now >= start + (AFTER_MIN - 1) * 60_000L && now < dayStart + s.end * 60_000L && key(today, s) !in tried
        }
        if (due.isEmpty()) return
        SpectrumSync.run(c, attendanceOnly = true, background = true)
        val sessions = SpectrumStore.array(c, "attendance").objects()
        for (s in due) {
            tried += key(today, s)
            SpectrumStore.putStringSet(c, TRIED, tried)
            val from = dayStart + s.start * 60_000L
            val to = dayStart + s.end * 60_000L
            val session = sessions.firstOrNull { it.optString("code") == s.course.code && it.getLong("start") < to && it.getLong("end") > from }
            if (session == null) {
                Log.i(TAG, "auto attendance: ${s.course.code} ${s.type}: no attendance activity for this class")
                continue
            }
            val url = session.getString("url")
            val answer = mark(c, url, dry = false)
            Log.i(TAG, "auto attendance: ${s.course.code} ${s.type}: $answer")
            notify(c, s, session.getString("id"), url, answer)
        }
    }

    private const val TRIED = "autoAttendanceTried"
    private fun key(day: LocalDate, s: Session) = "$day ${s.start} ${s.course.code}"

    /** Says how it went, in the place of the session's "Attendance open" notification. */
    private fun notify(c: Context, s: Session, id: String, url: String, answer: JSONObject) {
        val what = "${s.course.code} ${s.type.lowercase()}"
        if (answer.optBoolean("ok")) {
            val status = answer.optString("status").ifBlank { "present" }.lowercase()
            Notify.post(c, Notify.ATTENDANCE, "att-$id", "Marked $status · $what", "Spectrum recorded your attendance at ${SpectrumStore.hm(System.currentTimeMillis())}.", Notify.openSpectrum(c, url))
            return
        }
        val why = when (answer.optString("error")) {
            "notopen", "closed" -> "No attendance session was open for it yet."
            "password" -> "The session needs your lecturer's password."
            "qr" -> "The session needs its QR code scanned in class."
            "network" -> "Spectrum only takes this session from UM's network: join the campus Wi-Fi."
            "nostatus" -> "Spectrum offered no status to choose."
            "signedout" -> "Spectrum wants you to sign in again."
            "unreachable" -> "Couldn't reach Spectrum."
            else -> "Spectrum didn't confirm it."
        }
        Notify.post(c, Notify.ATTENDANCE, "att-$id", "Couldn't mark attendance · $what", "$why Tap to mark it yourself.", Notify.openSpectrum(c, url))
    }

    /**
     * Opens [url], an attendance activity's page, in a hidden page and runs the script there: marks the
     * user, or ([dry]) only says what the page offers. Blocks (call it off the main thread) until it has
     * an answer, at most about a minute. Errors of its own: signedout (Microsoft wants the user), unreachable.
     */
    fun mark(c: Context, url: String, dry: Boolean): JSONObject {
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        var answer = JSONObject().put("error", "unreachable")
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
                answer = a
                web.destroy()
                done.countDown()
            }
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, page: String) {
                    if (over) return
                    val u = Uri.parse(page)
                    lastHost = u.host.orEmpty()
                    if (asked || lastHost != Uri.parse(Spectrum.SITE).host || u.path != "/mod/attendance/view.php") return
                    asked = true
                    ask(web, dry) { finish(it) }
                }
            }
            web.loadUrl(url)
            // Still on Microsoft's sign-in by then: it wants the user. Not a word at all: Spectrum is out of reach.
            main.postDelayed({
                if (!asked) finish(JSONObject().put("error", if (lastHost.endsWith("microsoftonline.com") || lastHost.endsWith("live.com")) "signedout" else "unreachable"))
            }, 30_000)
            main.postDelayed({ finish(JSONObject().put("error", "unreachable").put("detail", "no answer in time")) }, 60_000)
        }
        done.await(70, TimeUnit.SECONDS)
        return answer
    }

    /** Runs the script in [web]'s page and hands its answer to [done], over a port only Spectrum's page can take. */
    private fun ask(web: WebView, dry: Boolean, done: (JSONObject) -> Unit) {
        val (mine, theirs) = web.createWebMessageChannel()
        mine.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(port: WebMessagePort, message: WebMessage) {
                mine.close()
                done(runCatching { JSONObject(message.data) }.getOrElse { JSONObject().put("error", "failed").put("detail", "unreadable answer") })
            }
        })
        web.evaluateJavascript(web.context.assets.open(SCRIPT).bufferedReader().use { it.readText() }) {
            web.postWebMessage(WebMessage(JSONObject().put("dry", dry).toString(), arrayOf(theirs)), Uri.parse(Spectrum.SITE))
        }
    }

    private const val JOB_ID = 3102
}

/** The marking itself, off the alarm: a job may run longer than a broadcast. */
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
