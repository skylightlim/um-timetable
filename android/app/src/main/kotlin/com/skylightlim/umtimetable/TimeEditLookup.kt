package com.skylightlim.umtimetable

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebView
import org.json.JSONObject

/**
 * TimeEdit, UM's timetabling system: every class of a course with its room and lecturer. Its
 * requests only work inside TimeEdit's own page (requests made outside the WebView with its cookies
 * got the login page), so [request] runs assets/timeedit-lookup.js there and gets the answer over a
 * message port; [TimeEditTimetable] turns it into the app's timetable.
 */
object TimeEditLookup {

    /** The page script; it waits for the courses on a message port. */
    const val SCRIPT = "timeedit-lookup.js"

    // Not web.postDelayed: a WebView that isn't on screen never runs posted tasks.
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** The user's courses and occurrences: Spectrum's groups, else the app's timetable. */
    fun occurrences(context: Context): JSONObject {
        val out = JSONObject()
        Timetable.json(context).optJSONObject("courses")?.let { c ->
            for (code in c.keys()) c.getJSONObject(code).optInt("occ").takeIf { it > 0 }?.let { out.put(code, it) }
        }
        val spectrum = SpectrumStore.occurrences(context)
        for (code in spectrum.keys()) out.put(code, spectrum.getInt(code))
        return out
    }

    /**
     * Looks up [courses] ({"WIX1002": 10}) inside TimeEdit's page in [web] and calls [done] once,
     * with the script's answer or an error message.
     */
    fun request(web: WebView, courses: JSONObject, timeoutMs: Long, done: (JSONObject?, String?) -> Unit) {
        val (mine, theirs) = web.createWebMessageChannel()
        var answered = false
        lateinit var timeout: Runnable
        fun settle(answer: JSONObject?, error: String?) {
            if (answered) return
            answered = true
            main.removeCallbacks(timeout)
            mine.close()
            done(answer, error)
        }
        timeout = Runnable { settle(null, "TimeEdit didn't answer. Keep this screen open while it looks; Android blocks this app's network in the background.") }
        mine.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(port: WebMessagePort, message: WebMessage) {
                val answer = runCatching { JSONObject(message.data) }.getOrNull()
                if (answer != null && answer.has("slots")) return settle(answer, null)
                val error = answer?.optString("error").orEmpty()
                settle(
                    null,
                    when {
                        answer?.optBoolean("signedOut") == true -> "TimeEdit signed you out. Sign in again, then try once more."
                        error.contains("Failed to fetch") -> "Couldn't reach TimeEdit. Check your connection and keep this screen open while it looks."
                        else -> "TimeEdit lookup failed: ${error.ifBlank { "no answer" }}"
                    },
                )
            }
        })
        main.postDelayed(timeout, timeoutMs)
        web.evaluateJavascript(web.context.assets.open(SCRIPT).bufferedReader().use { it.readText() }) {
            // Only a page from TimeEdit's origin can receive the port, so no other page sees the courses or answers.
            web.postWebMessage(WebMessage(JSONObject().put("courses", courses).toString(), arrayOf(theirs)), Uri.parse("https://cloud.timeedit.net"))
        }
    }

    /**
     * Signs [web] in to TimeEdit without the user when Microsoft already knows them (they signed in
     * to Spectrum): on TimeEdit's sign-in page it clicks "Log in", on its choice page "Sign in with
     * SSO", once per page, never typing. Microsoft's own pages are left to the user. Call
     * [pageFinished] from the WebViewClient; [onState] gets whether the student page is signed in.
     */
    class SignIn(private val web: WebView, private val onState: (signedIn: Boolean) -> Unit) {
        private var page = 0

        fun pageFinished(url: String) {
            val gen = ++page
            val uri = Uri.parse(url)
            Log.i("UMTimetable", "TimeEdit sign-in: page ${uri.host}${uri.path}")
            when {
                url.startsWith(TimeEditActivity.SITE) -> web.evaluateJavascript(STATUS) { status ->
                    Log.i("UMTimetable", "TimeEdit sign-in: student page answers $status")
                    if (gen != page) return@evaluateJavascript
                    val signedIn = status == "200"
                    onState(signedIn)
                    // TimeEdit first tries a token silently (up to 5 s), then shows Log in.
                    if (!signedIn) click(gen, LOG_IN, 10)
                }
                // TimeEdit's sign-in app draws its choices once its settings load: give it time.
                uri.host == "www.timeedit.net" -> click(gen, SSO, 25)
            }
        }

        private fun click(gen: Int, choice: String, tries: Int) {
            if (gen != page) return
            web.evaluateJavascript(clickScript(choice)) { clicked ->
                if (clicked == "true") Log.i("UMTimetable", "TimeEdit sign-in: clicked /$choice/")
                if (clicked != "true" && tries > 1) main.postDelayed({ click(gen, choice, tries - 1) }, 1_000)
            }
        }
    }

    // The student page answers 412 with TimeEdit's sign-in page until the user is signed in.
    private const val STATUS = "(() => { const x = new XMLHttpRequest(); x.open('GET', '/my_um/web/students/', false); x.send(); return x.status })()"
    private const val LOG_IN = "^\\s*log ?in\\s*$"
    private const val SSO = "\\bsso\\b"

    /** Clicks the first visible button or link whose text matches [pattern]; answers whether it did. */
    private fun clickScript(pattern: String) = """(() => {
        const r = new RegExp(${JSONObject.quote(pattern)}, 'i');
        const e = [...document.querySelectorAll('a, button, input[type=submit], input[type=button], [role=button]')]
            .find(e => (e.offsetParent !== null || e.getClientRects().length) && r.test((e.innerText || e.value || '').trim()));
        if (e) e.click();
        return !!e;
    })()"""

    /** "ASSOCIATE PROF. DR." + "DANIEL BIN ISMAIL" → "Assoc. Prof. Dr. Daniel bin Ismail". */
    fun displayName(title: String, fullName: String): String {
        val t = words(title.uppercase().replace("ASSOCIATE PROF.", "ASSOC. PROF.").replace("PROF. MADYA", "ASSOC. PROF."))
        return listOf(t, words(fullName)).filter { it.isNotBlank() }.joinToString(" ")
    }

    private val LOWER = setOf("bin", "binti", "bt", "bte", "a/l", "a/p", "van", "de")
    private fun words(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { w ->
        val lower = w.lowercase()
        if (lower in LOWER) lower else lower.replaceFirstChar { it.uppercase() }
    }

    private val TITLES = setOf("prof", "assoc", "associate", "madya", "ts", "dr", "ir", "sr", "en", "encik", "pn", "puan", "cik", "madam", "mr", "mrs", "ms", "miss", "dato", "datin", "datuk", "sri", "seri", "tan", "haji", "hajah", "hj", "hjh")
    private val CONNECTORS = setOf("bin", "binti", "bt", "bte", "a/l", "a/p", "@")
    private fun tokens(name: String) = name.lowercase().replace("'", "").split(Regex("[\\s.,]+"))
        .filter { it.isNotEmpty() && it !in TITLES && it !in CONNECTORS }.toSet()

    /**
     * Whether two spellings name the same person: most of the shorter name's words are in the other.
     * "Dr. Aina" and TimeEdit's "AINA BINTI HASSAN" match; so do "Ma'arof" and TimeEdit's "MA AROF".
     */
    fun samePerson(a: String, b: String): Boolean {
        val x = tokens(a)
        val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return (x intersect y).size.toFloat() / minOf(x.size, y.size) >= 0.6f
    }
}
