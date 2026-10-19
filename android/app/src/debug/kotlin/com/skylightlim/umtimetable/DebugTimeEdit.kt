package com.skylightlim.umtimetable

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Debug only: what "Get timetable from TimeEdit" would build, on a hidden page, compared class by
 * class with the app's timetable. Nothing is saved. Signs in the way the screen does
 * ([TimeEditLookup.SignIn]); logs each class built and the differences.
 *
 *   adb shell am broadcast -n com.skylightlim.umtimetable/.DebugSpectrumReceiver --es timeedit build
 */
object DebugTimeEdit {
    private const val TAG = "UMTimetable"

    fun build(c: Context, done: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val web = WebView(c).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        var over = false
        fun finish(line: String) {
            if (over) return
            over = true
            Log.i(TAG, "timeedit build: $line")
            web.destroy()
            done()
        }
        val courses = TimeEditLookup.occurrences(c)
        Log.i(TAG, "timeedit build: courses $courses (Spectrum: ${SpectrumStore.occurrences(c)})")
        val began = System.currentTimeMillis()
        var asked = false
        val signIn = TimeEditLookup.SignIn(web) { signedIn ->
            if (!signedIn || asked) return@SignIn
            asked = true
            Log.i(TAG, "timeedit build: signed in after ${System.currentTimeMillis() - began} ms")
            TimeEditLookup.request(web, courses, 45_000) { answer, error ->
                if (answer == null) return@request finish("FAILED: $error")
                Log.i(TAG, "timeedit build: occurrences offered ${answer.optJSONObject("offered")}")
                val current = Timetable.local(c)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
                val names = SpectrumStore.array(c, "courses").objects().associate { it.optString("code") to it.optString("name") }
                val result = TimeEditTimetable.build(answer, courses, names, current) ?: return@request finish("no weekly classes")
                for (s in result.json.getJSONArray("sessions").objects()) {
                    Log.i(TAG, "timeedit build class: ${s.optString("code")} ${DAY_SHORT[s.optInt("day")]} ${s.optString("start")}-${s.optString("end")} ${s.optString("type")} ${s.optString("room")} (${s.optString("roomName")}) · ${s.opt("lecturer")}")
                }
                val diff = TimeEditTimetable.compare(current, result.json)
                (diff.changed.map { "changed: $it" } + diff.added.map { "new: $it" } + diff.removed.map { "gone: $it" }).forEach { Log.i(TAG, "timeedit build diff: $it") }
                finish("${result.classes} classes, ${result.courses} courses, term ${result.term}, not found ${result.notFound}; vs app: ${diff.same} same, ${diff.changed.size} changed, ${diff.added.size} new, ${diff.removed.size} gone; ${System.currentTimeMillis() - began} ms")
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!over) signIn.pageFinished(url)
            }
        }
        web.loadUrl(TimeEditActivity.START)
        main.postDelayed({
            if (over) return@postDelayed
            // What the stuck page offers: its visible buttons and links (addresses masked).
            web.evaluateJavascript("""(() => [...document.querySelectorAll('a, button, input[type=submit], input[type=button], [role=button]')]
                .filter(e => e.offsetParent !== null || e.getClientRects().length)
                .map(e => (e.innerText || e.value || '').trim().replace(/\S+@\S+/g, '<email>').slice(0, 40)).filter(t => t).join(' | '))()""") { buttons ->
                Log.i(TAG, "timeedit build: stuck at ${web.url?.let { android.net.Uri.parse(it).let { u -> "${u.host}${u.path}" } }}, title ${web.title}, buttons: $buttons")
                finish("timed out (signed in: $asked)")
            }
        }, 52_000)
    }
}
