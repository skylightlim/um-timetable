package com.skylightlim.umtimetable

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A course page's layout as Spectrum's own page lays it out, for any course format: which section sits in
 * which (Flexible sections), which Spectrum shows as a link to a page of its own, and which activities
 * each section lists, in its order (one hidden from the course page isn't). It's the description Moodle
 * builds the page from, core_courseformat_get_state, which the app's web-service token may not call
 * ("Access control exception"); so it's read the way the page reads it, in a hidden page on the app's
 * Spectrum session, signed in through Microsoft like auto attendance's. Kept per course, and read again
 * once a day, or as soon as the course has a section the last reading didn't know.
 */
object CourseLayout {
    private const val TAG = "UMTimetable"
    private const val DAY = 86_400_000L
    /** A small signed-in page: it carries the session key the request needs. */
    private const val PAGE = "/user/preferences.php"
    /**
     * Formats whose page lists the web service's sections in its own order: such a page shows at once,
     * and its layout follows. Any other (Flexible sections, Tiles, one not seen yet) waits for it the
     * first time, since the web service's flat list can be far from what the page shows.
     */
    val PLAIN = setOf("topics", "weeks")
    /** The kept layout's shape; an older one is read again. */
    private const val VERSION = 2

    /** { "<section id>": { parent: <section id, 0 at the top>, link, cms: [activity ids in the page's order] } } as last kept, or null. */
    fun kept(c: Context, courseId: Int): JSONObject? =
        SpectrumStore.read(c, "layout-$courseId")?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.optInt("v") == VERSION }?.optJSONObject("sections")

    /** Whether the kept layout should be read again: none, a day old, or missing one of [course]'s sections. */
    fun stale(c: Context, courseId: Int, course: JSONObject): Boolean {
        val kept = SpectrumStore.read(c, "layout-$courseId")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val sections = kept?.takeIf { it.optInt("v") == VERSION }?.optJSONObject("sections") ?: return true
        val ids = course.optJSONArray("sections")?.objects().orEmpty().map { it.optInt("id").toString() }
        return System.currentTimeMillis() - kept.optLong("at") > DAY || !ids.all(sections::has)
    }

    /** Reads the layout afresh and keeps it; null when it can't be read (signed out of Microsoft, no network). */
    fun refresh(c: Context, courseId: Int): JSONObject? {
        val fresh = read(c, courseId) ?: return null
        SpectrumStore.write(c, "layout-$courseId", JSONObject().put("v", VERSION).put("at", System.currentTimeMillis()).put("sections", fresh).toString())
        return fresh
    }

    /** Reads the layout in a hidden page. Blocks (call it off the main thread) for at most about 30 s. */
    private fun read(c: Context, courseId: Int): JSONObject? {
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        var answer: JSONObject? = null
        val started = System.currentTimeMillis()
        main.post {
            val web = WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.blockNetworkImage = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
            var over = false
            var asked = false
            fun finish(a: JSONObject?, why: String) {
                if (over) return
                over = true
                answer = a
                Log.i(TAG, "course layout $courseId: $why in ${System.currentTimeMillis() - started} ms")
                web.destroy()
                done.countDown()
            }
            // The answer comes back asynchronously; the page keeps it until asked.
            fun poll() {
                if (over) return
                web.evaluateJavascript("window.__umLayout || ''") { value ->
                    val text = runCatching { JSONTokener(value).nextValue() as? String }.getOrNull().orEmpty()
                    val json = text.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                    when {
                        json == null -> main.postDelayed(::poll, 250)
                        json.has("error") -> finish(null, "failed (${json.optString("error")})")
                        else -> finish(json, "read ${json.length()} sections")
                    }
                }
            }
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, page: String) {
                    val u = Uri.parse(page)
                    // Until then it's signing in (Microsoft, then back to Spectrum).
                    if (over || asked || u.host != Uri.parse(Spectrum.SITE).host || u.path != PAGE) return
                    asked = true
                    view.evaluateJavascript(script(courseId)) { poll() }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) finish(null, "page failed (${error.description})")
                }
            }
            web.loadUrl(Spectrum.SITE + PAGE)
            main.postDelayed({ finish(null, if (asked) "no answer" else "not signed in") }, 30_000)
        }
        done.await(35, TimeUnit.SECONDS)
        return answer
    }

    private fun script(courseId: Int) = """
        (function () {
          window.__umLayout = '';
          fetch(M.cfg.wwwroot + '/lib/ajax/service.php?sesskey=' + M.cfg.sesskey + '&info=core_courseformat_get_state', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify([{index: 0, methodname: 'core_courseformat_get_state', args: {courseid: $courseId}}])
          }).then(function (r) { return r.json(); }).then(function (r) {
            if (r[0].error) throw new Error((r[0].exception && r[0].exception.errorcode) || 'error');
            var out = {};
            JSON.parse(r[0].data).section.forEach(function (s) {
              out[s.id] = {parent: Number(s.parentid) || 0, link: !!s.showaslink, cms: (s.cmlist || []).map(Number)};
            });
            window.__umLayout = JSON.stringify(out);
          }).catch(function (e) { window.__umLayout = JSON.stringify({error: String(e)}); });
        })();
    """.trimIndent()
}
