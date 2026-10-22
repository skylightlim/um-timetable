package com.skylightlim.umtimetable

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import org.json.JSONObject

/**
 * The timetable from TimeEdit, UM's timetabling system: every weekly class of the user's courses
 * and occurrences (from Spectrum), with rooms and lecturers. TimeEdit signs in with SiswaMail ("Sign
 * in with SSO"). When Microsoft already knows the user (they signed in to Spectrum), [TimeEditLookup.SignIn]
 * clicks through TimeEdit's own buttons and the user types nothing; otherwise Microsoft's page is
 * right here for them. The timetable is read as soon as the page is signed in.
 */
class TimeEditActivity : WebScreen() {
    private lateinit var signIn: TimeEditLookup.SignIn
    private var started = false
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUp(
            "TimeEdit", "Get your timetable", "Close",
            "Signing in to TimeEdit with your SiswaMail…",
            "Get timetable",
        )
        action.isEnabled = false
        action.setOnClickListener { fetch() }
        signIn = TimeEditLookup.SignIn(web) { signedIn ->
            action.isEnabled = signedIn && !running
            if (signedIn && !started) fetch() else if (!signedIn && !running) status.text = "Signing in to TimeEdit with your SiswaMail…"
        }
        web.loadUrl(START)
    }

    override fun pageFinished(url: String) {
        signIn.pageFinished(url)
        if (hostIn(Uri.parse(url).host, "microsoftonline.com")) {
            status.text = "Microsoft wants you to sign in: use your SiswaMail. Your password goes only to Microsoft; this app never sees it."
        }
    }

    private fun fetch() {
        if (running) return
        val courses = TimeEditLookup.occurrences(this)
        if (courses.length() == 0) {
            status.text = "Connect Spectrum first (in the Courses tab): it tells the app your courses and occurrences."
            return
        }
        started = true
        running = true
        action.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Reading your ${courses.length()} courses on TimeEdit…"
        val began = System.currentTimeMillis()
        val registered = TimeEditLookup.registered(this)
        TimeEditLookup.request(web, courses, 90_000, registered) { answer, error ->
            running = false
            progress.visibility = View.INVISIBLE
            action.isEnabled = true
            if (answer == null) {
                Log.w("UMTimetable", "TimeEdit lookup failed: $error")
                status.text = error
                return@request
            }
            // Every occurrence of each course, for choosing one in Settings; kept for courses not looked up this time.
            answer.optJSONObject("offered")?.let { offered ->
                val options = SpectrumStore.occurrenceOptions(this)
                for (code in offered.keys()) offered.optJSONArray(code)?.takeIf { it.length() > 0 }?.let { options.put(code, it) }
                SpectrumStore.write(this, "occurrence-options", options.toString())
            }
            val current = Timetable.local(this)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            val names = SpectrumStore.array(this, "courses").objects().associate { it.optString("code") to it.optString("name") }
            val result = TimeEditTimetable.build(answer, courses, names, current, registered)
            Log.i("UMTimetable", "TimeEdit timetable: ${result?.classes ?: 0} classes, ${result?.courses ?: 0} courses, not found ${result?.notFound}, ${result?.term}, in ${System.currentTimeMillis() - began} ms")
            if (result == null) {
                status.text = "TimeEdit lists no weekly classes for your courses in the next six weeks."
                return@request
            }
            status.text = "Signed in to TimeEdit."
            offer(result, current)
        }
    }

    /** A first timetable is used straight away; one that would change the app's is shown first. */
    private fun offer(result: TimeEditTimetable.Result, current: JSONObject) {
        if ((current.optJSONArray("sessions")?.length() ?: 0) == 0) return use(result)
        val diff = TimeEditTimetable.compare(current, result.json)
        Log.i("UMTimetable", "TimeEdit vs app: ${diff.same} same, ${diff.changed.size} changed, ${diff.added.size} new, ${diff.removed.size} gone")
        (diff.changed + diff.added.map { "new: $it" } + diff.removed.map { "gone: $it" }).forEach { Log.i("UMTimetable", "TimeEdit diff: $it") }
        val lines = buildList {
            add("TimeEdit has ${result.classes} weekly classes in ${result.courses} courses${result.term?.let { " ($it)" }.orEmpty()}.")
            if (diff.identical) add("Your timetable already matches it.")
            else if (diff.same > 0) add("${diff.same} are the same as in the app.")
            if (diff.changed.isNotEmpty()) add("Changed:\n" + diff.changed.joinToString("\n") { "• $it" })
            if (diff.added.isNotEmpty()) add("New:\n" + diff.added.joinToString("\n") { "• $it" })
            if (diff.removed.isNotEmpty()) add("Not on TimeEdit, so they'd go:\n" + diff.removed.joinToString("\n") { "• $it" })
            if (result.notFound.isNotEmpty()) add("No classes on TimeEdit for ${result.notFound.joinToString(", ")}.")
        }
        val dialog = AlertDialog.Builder(this).setTitle("Timetable from TimeEdit").setMessage(lines.joinToString("\n\n"))
        if (diff.identical) dialog.setPositiveButton("OK") { _, _ -> finish() }
        else dialog.setPositiveButton("Use TimeEdit's timetable") { _, _ -> use(result) }.setNegativeButton("Keep mine", null)
        dialog.show()
    }

    private fun use(result: TimeEditTimetable.Result) {
        Timetable.setLocal(this, result.json.toString())
        TimetableWidget.updateAll(this)
        AttendanceWatch.schedule(this)
        Log.i("UMTimetable", "TimeEdit: saved the timetable, ${result.classes} classes")
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        const val SITE = "https://cloud.timeedit.net/my_um/"
        const val START = "${SITE}web/students/"
    }
}
