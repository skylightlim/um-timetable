package com.skylightlim.umtimetable

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Debug only: can MAYA and TimeEdit be entered with the SiswaMail session the app's web screens
 * already share, without the user typing anything? Each site opens on a hidden page. On the site's own
 * sign-in choice the probe clicks "SSO" (TimeEdit) or "Microsoft" (MAYA), nothing else, and it never
 * types. It logs each page's host, path, title and heading (no query strings, no page text), then
 * where it ended: signed in, or which Microsoft step wants the user.
 *
 *   adb shell am broadcast -n com.skylightlim.umtimetable/.DebugSpectrumReceiver --es sso probe [--es site timeedit] [--ez signout true]
 *
 * signout first signs the app out of TimeEdit only (expires its cookies, clears its sites' storage), so
 * the test shows whether Microsoft's session alone gets it back in. Microsoft's and Spectrum's stay.
 */
object SsoProbe {
    private const val TAG = "UMTimetable"
    private const val SETTLE_MS = 12_000L

    /** [choices]: what may be clicked on the site's own pages, most wanted first. */
    private class Site(val name: String, val start: String, val choices: List<String>, val choiceHost: String, val signedIn: (Uri) -> Boolean)

    private val SITES = listOf(
        Site(
            // TimeEdit's page first tries a token silently, else shows "Log in"; that leads to the choice of
            // "TE IdP" or "SSO" (Microsoft).
            "timeedit", TimeEditActivity.START, listOf("\\bsso\\b", "^\\s*log ?in\\s*$"), "timeedit.net",
            // TimeEdit answers its student page with 412 until signed in; checked with a request below.
            { it.host == "cloud.timeedit.net" && it.path.orEmpty().startsWith("/my_um/web/students") },
        ),
        Site(
            "maya", MayaActivity.LOGIN, listOf("microsoft"), "maya.um.edu.my",
            { it.host == "maya.um.edu.my" && !it.path.orEmpty().contains("siw_lgn", ignoreCase = true) },
        ),
    )

    private const val INFO = """(() => JSON.stringify({
        title: document.title.slice(0, 60),
        heading: ((document.querySelector('[role=heading], #loginHeader, h1, h2') || {}).innerText || '').trim().slice(0, 60),
        password: !!document.querySelector('input[type=password]')
    }))()"""

    private fun clickScript(choices: List<String>) = """(() => {
        const els = [...document.querySelectorAll('a, button, input[type=submit], input[type=button], [role=button], img')]
            .filter(e => e.offsetParent !== null || e.getClientRects().length);
        for (const p of ${org.json.JSONArray(choices)}) {
            const r = new RegExp(p, 'i');
            for (const e of els) {
                const t = (e.innerText || e.value || e.alt || e.title || '').trim();
                if (r.test(t)) { (e.tagName == 'IMG' && e.closest('a') ? e.closest('a') : e).click(); return 'clicked "' + t.slice(0, 40) + '"'; }
            }
        }
        return 'no sign-in choice found';
    })()"""

    private val TIMEEDIT = listOf("https://cloud.timeedit.net/my_um/", "https://cloud.timeedit.net/", "https://www.timeedit.net/", "https://auth.timeedit.net/", "https://organizations.timeedit.net/")

    /** Expires every cookie TimeEdit's sites send, under each domain and path it may have been set with. */
    private fun signOutOfTimeEdit(): Int {
        val cm = CookieManager.getInstance()
        val names = mutableSetOf<String>()
        for (u in TIMEEDIT) {
            val host = Uri.parse(u).host
            for (name in cm.getCookie(u)?.split(";")?.map { it.substringBefore("=").trim() }?.filter { it.isNotEmpty() }.orEmpty()) {
                names += name
                for (domain in listOf("", "; Domain=.timeedit.net", "; Domain=$host")) for (path in listOf("/", "/my_um", "/my_um/")) {
                    cm.setCookie(u, "$name=; Max-Age=0; Path=$path$domain; Secure")
                }
            }
            WebStorage.getInstance().deleteOrigin("https://$host")
        }
        cm.flush()
        val left = TIMEEDIT.sumOf { u -> cm.getCookie(u)?.split(";")?.count { it.isNotBlank() } ?: 0 }
        Log.i(TAG, "sso timeedit: signed out (${names.size} cookie names expired, $left cookies left)")
        return names.size
    }

    fun run(context: Context, only: String?, signOut: Boolean, done: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val sites = SITES.filter { only == null || it.name == only }
        if (signOut && sites.any { it.name == "timeedit" }) signOutOfTimeEdit()
        var left = sites.size
        var over = false
        val webs = mutableListOf<WebView>()
        fun finishAll() {
            if (over) return
            over = true
            webs.forEach { it.destroy() }
            done()
        }
        fun finish() {
            if (--left <= 0) finishAll()
        }
        for (site in sites) {
            var clickedOn = -1
            var finished = false
            var generation = 0
            val steps = mutableListOf<String>()
            fun report(result: String) {
                if (finished) return
                finished = true
                Log.i(TAG, "sso ${site.name}: $result after ${steps.size} pages")
                finish()
            }
            val web = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
            webs += web
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (finished || over) return
                    val uri = Uri.parse(url)
                    val gen = ++generation
                    view.evaluateJavascript(INFO) { raw ->
                        val info = runCatching { org.json.JSONObject(org.json.JSONTokener(raw).nextValue() as String) }.getOrNull()
                        val line = "${uri.host}${uri.path} · ${info?.optString("title")} · ${info?.optString("heading")}${if (info?.optBoolean("password") == true) " · PASSWORD FIELD" else ""}"
                        steps += line
                        Log.i(TAG, "sso ${site.name} [${steps.size}] $line")
                        // On the site's own pages, click its sign-in choice once per page (retrying while it's drawn).
                        fun tryClick(n: Int) {
                            if (clickedOn == gen || finished || over || gen != generation) return
                            view.evaluateJavascript(clickScript(site.choices)) { r ->
                                if (r.contains("clicked")) clickedOn = gen
                                if (clickedOn == gen || n == 6) Log.i(TAG, "sso ${site.name}: $r")
                                else main.postDelayed({ tryClick(n + 1) }, 1_000)
                            }
                        }
                        val ownPage = uri.host.orEmpty().endsWith(site.choiceHost)
                        if (site.name == "timeedit" && site.signedIn(uri)) {
                            // The student page and its sign-in page share a URL: ask for the page's status.
                            val check = "(() => { const x = new XMLHttpRequest(); x.open('GET', '${TimeEditActivity.START}', false); x.send(); return x.status })()"
                            view.evaluateJavascript(check) { status ->
                                Log.i(TAG, "sso timeedit: student page answers $status")
                                if (status == "200") report("SIGNED IN, no typing") else main.postDelayed({ tryClick(1) }, 2_000)
                            }
                        } else if (site.signedIn(uri)) {
                            report("SIGNED IN, no typing (${uri.path})")
                        } else if (ownPage) {
                            tryClick(1)
                        }
                        // No further page within SETTLE_MS: the flow stopped and wants the user (or failed).
                        main.postDelayed({
                            if (gen == generation) report("STOPPED at ${uri.host}${uri.path}: ${info?.optString("heading").orEmpty().ifEmpty { info?.optString("title").orEmpty() }}")
                        }, SETTLE_MS)
                    }
                }
            }
            web.loadUrl(site.start)
        }
        main.postDelayed({
            if (over) return@postDelayed
            Log.i(TAG, "sso probe: timed out")
            finishAll()
        }, 50_000)
    }
}
