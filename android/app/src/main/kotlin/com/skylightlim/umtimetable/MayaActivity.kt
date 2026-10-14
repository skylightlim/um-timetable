package com.skylightlim.umtimetable

import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONTokener

/**
 * MAYA (UM's SITS e:Vision portal) has the real timetable: occurrence and venue, but no lecturer.
 * The user signs in here themselves (SiswaMail or MAYA password, typed into MAYA's or Microsoft's
 * own page): MAYA has no single sign-on, so that step stays theirs. Then the app does the rest. It
 * opens Timetable › My Timetable (SIW_XTTB, "Timetable View", the path in UM's MAYA guide), waits
 * for the classes to appear and reads them. Only that page is read, in memory; it's never saved.
 * "Read this page" stays for when MAYA looks different and the app can't find its way.
 */
class MayaActivity : WebScreen() {
    private val main = Handler(Looper.getMainLooper())
    private var site = SITE
    /** Pages the app has opened by itself: a MAYA that looks different mustn't send it wandering. */
    private var hops = 0
    /** Checks so far for the timetable's classes (MAYA draws them after the page loads); -1 when not waiting. */
    private var polls = -1
    private var lastCount = -1
    /** The timetable has been read by itself once; after that only the button reads. */
    private var autoRead = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds only: a stand-in MAYA to test the steps against (DebugSpectrumReceiver --es mayaSite …).
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) intent.getStringExtra(EXTRA_SITE)?.let { site = it }
        setUp(
            "Import timetable from MAYA", "Close",
            "Sign in to MAYA, and the app opens your timetable and reads it. Your password goes only to MAYA or Microsoft; this app never sees it.",
            "Read this page",
        )
        action.isEnabled = false
        action.setOnClickListener { capture(auto = false) }
        web.loadUrl(site + LOGIN_PATH)
    }

    override fun onDestroy() {
        stopWaiting()
        super.onDestroy()
    }

    override fun pageStarted(url: String) {
        stopWaiting()
        main.removeCallbacks(lost)
    }

    override fun pageFinished(url: String) {
        val onMaya = url.startsWith(site)
        val onLogin = url.contains("siw_lgn", ignoreCase = true)
        action.isEnabled = onMaya && !onLogin
        if (!onMaya || onLogin || autoRead) return
        web.evaluateJavascript(LOOK) { result ->
            val page = parse(result) ?: return@evaluateJavascript
            val link = page.optJSONObject("link")
            when {
                page.optBoolean("onTimetable") -> waitForClasses()
                link != null && hops < MAX_HOPS -> {
                    hops++
                    Log.i(TAG, "MAYA: on \"${page.optString("title")}\", opening \"${link.optString("text")}\" (${link.optString("href").substringBefore('?')})")
                    status.text = "Signed in. Opening your timetable…"
                    progress.visibility = View.VISIBLE
                    // A menu link that only runs a script gets clicked; if nothing opens, the user takes over.
                    if (link.optBoolean("page")) web.loadUrl(link.getString("href"))
                    else web.evaluateJavascript("window.__umTimetableLink.click()", null)
                    main.postDelayed(lost, 12_000)
                }
                else -> {
                    Log.i(TAG, "MAYA: no way to the timetable on \"${page.optString("title")}\" after $hops pages")
                    lost.run()
                }
            }
        }
    }

    /** The app couldn't find the timetable by itself: the user opens it and taps the button. */
    private val lost = Runnable {
        progress.visibility = View.INVISIBLE
        status.text = "Signed in. Open Timetable › My Timetable in MAYA, then tap Read this page."
    }

    private fun waitForClasses() {
        if (polls >= 0) return
        polls = 0
        lastCount = -1
        status.text = "Reading your timetable…"
        progress.visibility = View.VISIBLE
        poll()
    }

    private val pollNext = Runnable { poll() }

    /** Every half second: once the classes on the page stop changing, it's read. */
    private fun poll() {
        web.evaluateJavascript(COUNT) { result ->
            if (polls < 0) return@evaluateJavascript
            val count = parse(result) ?: JSONObject()
            val classes = count.optInt("classes")
            polls++
            when {
                classes > 0 && classes == lastCount -> {
                    stopWaiting()
                    capture(auto = true)
                }
                polls >= MAX_POLLS -> {
                    stopWaiting()
                    Log.i(TAG, "MAYA: timetable page still has ${count.optInt("days")} days, $classes classes after ${polls / 2} s")
                    if (count.optInt("days") > 0) capture(auto = true) else lost.run()
                }
                else -> {
                    lastCount = classes
                    main.postDelayed(pollNext, 500)
                }
            }
        }
    }

    private fun stopWaiting() {
        polls = -1
        main.removeCallbacks(pollNext)
    }

    /** Reads the current page (and any same-origin frames) as rendered, in memory. */
    private fun capture(auto: Boolean) {
        val script = """
            (() => {
              const frames = [...document.querySelectorAll('iframe,frame')].map(f => {
                try { return { src: f.src, html: f.contentDocument.documentElement.outerHTML }; } catch (e) { return { src: f.src, html: null }; }
              });
              return JSON.stringify({ url: location.href, title: document.title, html: document.documentElement.outerHTML, frames });
            })()
        """.trimIndent()
        if (auto) autoRead = true
        action.isEnabled = false
        web.evaluateJavascript(script) { result ->
            action.isEnabled = true
            progress.visibility = View.INVISIBLE
            val page = parse(result)
            if (page == null) {
                Toast.makeText(this, "Couldn't read this page.", Toast.LENGTH_LONG).show()
                return@evaluateJavascript
            }
            Log.i(TAG, "MAYA page read${if (auto) " by itself" else ""}: ${page.optString("title")} (${page.optString("html").length} chars)")
            offer(page.optString("html"), auto)
        }
    }

    /**
     * Shows what was found. A first timetable is used straight away, as TimeEdit's is; one that would
     * replace the app's is used only if the user agrees.
     */
    private fun offer(html: String, auto: Boolean) {
        val found = MayaImport.timetable(this, MayaImport.events(html))
        if (found == null) {
            status.text = "Signed in."
            AlertDialog.Builder(this)
                .setTitle("No classes on this page")
                .setMessage(
                    if (auto) "MAYA's timetable shows no classes in the weeks on screen. If that's wrong, move it to a teaching week, then tap Read this page."
                    else "Open MAYA's Timetable View (the list of your classes by day), then tap Read this page again.",
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        if (Timetable.load(this).sessions.isEmpty()) return use(found)
        val weeks = if (found.weeks == 1) "1 week" else "${found.weeks} weeks"
        val lecturers = when (found.lecturersKept) {
            found.classes -> "MAYA doesn't list lecturers, so the names already in the app are kept for all ${found.classes} classes."
            0 -> "MAYA doesn't list lecturers, and none of these classes match one in the app, so they'll show as not listed."
            else -> "MAYA doesn't list lecturers. Names already in the app are kept for ${found.lecturersKept} classes that match exactly; " +
                "the other ${found.classes - found.lecturersKept} will show as not listed."
        }
        status.text = "Signed in. Your timetable is read."
        AlertDialog.Builder(this)
            .setTitle("Found ${found.classes} weekly classes")
            .setMessage(
                "${found.term}: ${found.courses} courses, from $weeks of MAYA's timetable. This replaces the timetable in the app.\n\n$lecturers",
            )
            .setPositiveButton("Use this timetable") { _, _ -> use(found) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun use(found: MayaImport.Result) {
        Timetable.setLocal(this, found.json.toString())
        TimetableWidget.updateAll(this)
        AttendanceWatch.schedule(this)
        Log.i(TAG, "MAYA import: ${found.classes} classes, ${found.courses} courses, ${found.lecturersKept} lecturers kept, ${found.firstDate}..${found.lastDate}")
        setResult(RESULT_OK)
        finish()
    }

    private fun parse(result: String?): JSONObject? =
        runCatching { JSONObject(JSONTokener(result).nextValue() as String) }.getOrNull()

    companion object {
        const val SITE = "https://maya.um.edu.my/"
        private const val LOGIN_PATH = "sitsvision/wrd/siw_lgn"
        const val LOGIN = SITE + LOGIN_PATH
        const val EXTRA_SITE = "mayaSite"
        private const val TAG = "UMTimetable"
        private const val MAX_HOPS = 3
        /** 15 seconds of half-second checks. */
        private const val MAX_POLLS = 30

        /**
         * Where this page is, and the best link towards the timetable: MAYA's own page name (SIW_XTTB),
         * else "My Timetable" (or its Malay), else a timetable link that isn't the search or exams.
         * `page` says whether it opens a page, rather than only running a script or opening a menu.
         */
        private val LOOK = """
            (() => {
              const onTimetable = /SIW_XTTB/i.test(location.href) || /timetable view/i.test(document.title)
                || document.querySelector('[data-jqtt-date], li.sitsjqtteventcontainer') !== null;
              const score = a => {
                const t = (a.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                if (/SIW_XTTB/i.test(a.getAttribute('href') || '')) return 3;
                if (t === 'my timetable' || t === 'jadual waktu saya') return 2;
                if (/timetable|jadual waktu/.test(t) && !/search|exam|carian|peperiksaan/.test(t)) return 1;
                return 0;
              };
              let best = null, top = 0;
              for (const a of document.querySelectorAll('a')) { const s = score(a); if (s > top) { best = a; top = s; } }
              window.__umTimetableLink = best;
              const href = best ? best.href : '';
              const page = /^https?:/.test(href) && href.split('#')[0] !== location.href.split('#')[0];
              return JSON.stringify({
                title: document.title, onTimetable,
                link: best && { text: best.textContent.replace(/\s+/g, ' ').trim().slice(0, 60), href, page },
              });
            })()
        """.trimIndent()

        private val COUNT = """
            JSON.stringify({ days: document.querySelectorAll('[data-jqtt-date]').length, classes: document.querySelectorAll('li.sitsjqtteventcontainer').length })
        """.trimIndent()
    }
}
