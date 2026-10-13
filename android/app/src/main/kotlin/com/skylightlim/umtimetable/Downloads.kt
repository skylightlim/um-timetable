package com.skylightlim.umtimetable

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Downloads a Spectrum file into Download/UM Spectrum/<course>/ and opens it.
 * Files go through MediaStore, so no storage permission is needed, and a file
 * already downloaded at the same size opens straight away.
 */
object Downloads {

    fun open(activity: Activity, url: String, name: String, mime: String?, size: Long, folder: String) {
        val token = SpectrumStore.token(activity)
        if (token == null) {
            Toast.makeText(activity, "Sign in to Spectrum first.", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = name.substringAfterLast('/').replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "file" }
        val type = mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val dir = "Download/UM Spectrum/${folder.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "Spectrum" }}/"
        Toast.makeText(activity, "Opening $fileName…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val uri = existing(activity, dir, fileName, size) ?: download(activity, authed(url, token), dir, fileName, type)
                activity.runOnUiThread { view(activity, uri, type) }
            } catch (e: Exception) {
                activity.runOnUiThread { Toast.makeText(activity, "Couldn't download $fileName: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Spectrum files are fetched through the web-service file endpoint, which takes the token. */
    fun authed(url: String, token: String): String {
        val ws = url.replace("${Spectrum.SITE}/pluginfile.php", "${Spectrum.SITE}/webservice/pluginfile.php")
        return if (ws.startsWith("${Spectrum.SITE}/webservice/pluginfile.php")) ws + (if ('?' in ws) "&" else "?") + "token=" + token else ws
    }

    private fun existing(activity: Activity, dir: String, name: String, size: Long): Uri? {
        val resolver = activity.contentResolver
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE),
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(dir, name), null,
        )?.use { cur ->
            if (cur.moveToFirst()) {
                val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cur.getLong(0).toString())
                if (size <= 0 || cur.getLong(1) == size) return uri
                resolver.delete(uri, null, null) // the lecturer replaced it; fetch the new one
            }
        }
        return null
    }

    private fun download(activity: Activity, url: String, dir: String, name: String, type: String): Uri {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            // Moodle reports errors (e.g. an expired token) as JSON instead of the file.
            if (conn.contentType?.startsWith("application/json") == true && !type.contains("json")) {
                error(conn.inputStream.bufferedReader().use { it.readText() }.let { Regex("\"message\":\"([^\"]+)").find(it)?.groupValues?.get(1) ?: "Spectrum refused the download" })
            }
            val resolver = activity.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, type)
                put(MediaStore.Downloads.RELATIVE_PATH, dir)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("couldn't create the file")
            try {
                resolver.openOutputStream(uri)!!.use { out -> conn.inputStream.use { it.copyTo(out) } }
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } finally {
            conn.disconnect()
        }
    }

    private fun view(activity: Activity, uri: Uri, type: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, "Saved to Downloads/UM Spectrum. No app here can open this file type.", Toast.LENGTH_LONG).show()
        }
    }
}
