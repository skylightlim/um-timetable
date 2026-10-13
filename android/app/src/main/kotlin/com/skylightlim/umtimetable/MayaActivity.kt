package com.skylightlim.umtimetable

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONTokener

/**
 * MAYA (UM's SITS e:Vision portal) has the real timetable: occurrence and venue, but no lecturer.
 * The user signs in here themselves (SiswaMail or MAYA password, typed into MAYA's or
 * Microsoft's own page), opens their timetable, and taps "Read this page". Only that page
 * is read, in memory; it's never saved.
 */
class MayaActivity : WebScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUp(
            "Import timetable from MAYA", "Close",
            "Sign in to MAYA. Your password goes only to MAYA or Microsoft; this app never sees it.",
            "Read this page",
        )
        action.isEnabled = false
        action.setOnClickListener { capture() }
        web.loadUrl(LOGIN)
    }

    override fun pageFinished(url: String) {
        val onMaya = url.startsWith("https://maya.um.edu.my/")
        val onLogin = url.contains("siw_lgn", ignoreCase = true)
        action.isEnabled = onMaya && !onLogin
        if (onMaya && !onLogin) status.text = "Signed in. Open your timetable in MAYA, then tap Read this page."
    }

    /** Reads the current page (and any same-origin frames) as rendered, and saves it on the phone. */
    private fun capture() {
        val script = """
            (() => {
              const frames = [...document.querySelectorAll('iframe,frame')].map(f => {
                try { return { src: f.src, html: f.contentDocument.documentElement.outerHTML }; } catch (e) { return { src: f.src, html: null }; }
              });
              return JSON.stringify({ url: location.href, title: document.title, html: document.documentElement.outerHTML, frames });
            })()
        """.trimIndent()
        action.isEnabled = false
        web.evaluateJavascript(script) { result ->
            action.isEnabled = true
            val json = runCatching { JSONTokener(result).nextValue() as String }.getOrNull()
            if (json == null) {
                Toast.makeText(this, "Couldn't read this page.", Toast.LENGTH_LONG).show()
                return@evaluateJavascript
            }
            val page = JSONObject(json)
            Log.i("UMTimetable", "MAYA page read: ${page.optString("title")} (${json.length} chars)")
            offer(page.optString("html"))
        }
    }

    /** Shows what was found and replaces the app's timetable only if the user agrees. */
    private fun offer(html: String) {
        val found = MayaImport.timetable(this, MayaImport.events(html))
        if (found == null) {
            AlertDialog.Builder(this)
                .setTitle("No classes on this page")
                .setMessage("Open MAYA's Timetable View (the list of your classes by day), then tap Read this page again.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val weeks = if (found.weeks == 1) "1 week" else "${found.weeks} weeks"
        val lecturers = when (found.lecturersKept) {
            found.classes -> "MAYA doesn't list lecturers, so the names already in the app are kept for all ${found.classes} classes."
            0 -> "MAYA doesn't list lecturers, and none of these classes match one in the app, so they'll show as not listed."
            else -> "MAYA doesn't list lecturers. Names already in the app are kept for ${found.lecturersKept} classes that match exactly; " +
                "the other ${found.classes - found.lecturersKept} will show as not listed."
        }
        AlertDialog.Builder(this)
            .setTitle("Found ${found.classes} weekly classes")
            .setMessage(
                "${found.term}: ${found.courses} courses, from $weeks of MAYA's timetable. This replaces the timetable in the app.\n\n$lecturers",
            )
            .setPositiveButton("Use this timetable") { _, _ ->
                Timetable.setLocal(this, found.json.toString())
                TimetableWidget.updateAll(this)
                AttendanceWatch.schedule(this)
                Log.i("UMTimetable", "MAYA import: ${found.classes} classes, ${found.courses} courses, ${found.lecturersKept} lecturers kept, ${found.firstDate}..${found.lastDate}")
                setResult(RESULT_OK)
                finish()
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    companion object {
        const val LOGIN = "https://maya.um.edu.my/sitsvision/wrd/siw_lgn"
    }
}
