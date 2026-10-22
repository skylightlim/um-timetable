package com.skylightlim.umtimetable

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.URLUtil

/**
 * Shows a Spectrum page (an assignment, an attendance session) inside the app. It
 * shares cookies with the sign-in screen, so it's usually already logged in; if
 * Spectrum's session has expired, its own login page appears.
 */
class SpectrumWebActivity : WebScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUp("Spectrum", "Opening the page…", "Done", compact = true)
        web.setDownloadListener { url, _, disposition, mime, length ->
            Downloads.open(this, url, URLUtil.guessFileName(url, disposition, mime), mime, length, "Spectrum")
        }
        web.loadUrl(intent.getStringExtra(EXTRA_URL) ?: "${Spectrum.SITE}/my/")
    }

    /** Spectrum and Microsoft's SiswaMail sign-in stay here; anything else opens in the browser. */
    override fun leaves(url: Uri): Boolean {
        if (url.scheme == "https" || url.scheme == "http") {
            if (hostIn(url.host, "um.edu.my", "microsoftonline.com", "microsoft.com", "live.com", "msauth.net", "msftauth.net")) return false
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return true
    }

    override fun pageFinished(url: String) {
        web.title?.takeIf { it.isNotBlank() && !it.startsWith("http") }?.let { title.text = it.substringBefore(" | ") }
    }

    companion object {
        const val EXTRA_URL = "url"

        fun open(activity: Activity, url: String) {
            val uri = Uri.parse(url)
            if (hostIn(uri.host, "um.edu.my")) {
                activity.startActivity(Intent(activity, SpectrumWebActivity::class.java).putExtra(EXTRA_URL, url))
            } else {
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
        }
    }
}
