package com.skylightlim.umtimetable

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Toast
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Signs in to Spectrum the way the official Moodle app does: open tool_mobile's
 * launch page, let the user sign in with SiswaMail (Microsoft 365), and catch the
 * `moodlemobile://token=…` redirect. The token is checked against the passport we
 * generated, then stored encrypted on this phone.
 */
class LoginActivity : WebScreen() {

    private val passport = SecureRandom().let { r -> (1..16).map { "0123456789"[r.nextInt(10)] }.joinToString("") }
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUp("SiswaMail", "Sign in to Spectrum", "Cancel", "Sign in with your SiswaMail. Your login stays on this phone; the app only talks to Spectrum.")
        web.loadUrl("${Spectrum.SITE}/admin/tool/mobile/launch.php?service=${Spectrum.SERVICE}&passport=$passport&urlscheme=moodlemobile")
    }

    /** Spectrum hands the token back as a moodlemobile:// link. */
    override fun leaves(url: Uri): Boolean {
        if (!url.scheme.equals("moodlemobile", ignoreCase = true)) return super.leaves(url)
        if (!handled) {
            handled = true
            finishLogin(url.toString())
        }
        return true
    }

    override fun pageStarted(url: String) {
        if (url.startsWith("moodlemobile:", ignoreCase = true)) {
            web.stopLoading()
            leaves(Uri.parse(url))
        }
    }

    private fun finishLogin(url: String) {
        val payload = Uri.decode(url.substringAfter("token=", "")).trim().trimEnd('/')
        val parts = runCatching { String(Base64.decode(payload, Base64.DEFAULT)).split(":::") }.getOrDefault(emptyList())
        val expected = md5(Spectrum.SITE + passport)
        if (parts.size < 2 || !parts[0].equals(expected, ignoreCase = true)) {
            fail("Spectrum's reply didn't match this sign-in. Please try again.")
            return
        }
        val token = parts[1]
        status.text = "Signed in. Checking your Spectrum account…"
        progress.visibility = View.VISIBLE
        web.visibility = View.INVISIBLE
        thread {
            try {
                val info = Spectrum.call(token, "core_webservice_get_site_info") as JSONObject
                SpectrumStore.saveLogin(this, token, info)
                Log.i("UMTimetable", "Spectrum sign-in ok; ${info.optJSONArray("functions")?.length() ?: 0} web-service functions available")
                runOnUiThread {
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread { fail("Couldn't check your Spectrum account: ${e.message}") }
            }
        }
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
