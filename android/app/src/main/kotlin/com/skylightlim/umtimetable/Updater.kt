package com.skylightlim.umtimetable

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Updates from the project's GitHub Releases. A release's tag is its version code ("v3" is versionCode 3);
 * its APK is the asset that ends in .apk. The app asks GitHub for the releases (no sign-in, nothing about
 * the user sent), and when one is newer than itself it offers it: download into its own storage, then
 * Android's installer, which takes it over the installed app only because the release key signs both.
 *
 * Pre-releases count: GitHub's "latest release" leaves them out, and every release so far is one, so the
 * list is read instead and the highest version code wins.
 */
object Updater {
    private const val RELEASES = "https://api.github.com/repos/skylightlim/um-timetable/releases?per_page=20"
    private val TAG = Regex("""^v(\d+)$""")
    /** At most one check on its own every 12 hours (GitHub allows 60 an hour without sign-in). */
    private const val EVERY = 12 * 3_600_000L

    data class Release(val code: Long, val name: String, val notes: String, val url: String, val size: Long)

    private fun prefs(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun installed(c: Context): Pair<Long, String> =
        c.packageManager.getPackageInfo(c.packageName, 0).let { it.longVersionCode to (it.versionName ?: "") }

    /** The newest release with an APK, or null if there's none. Network work: call it off the main thread. */
    fun latest(c: Context): Release? {
        val conn = URL(RELEASES).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "UM-Timetable/${installed(c).second}")
        try {
            if (conn.responseCode == 403 || conn.responseCode == 429) error("GitHub is busy; try again in an hour")
            if (conn.responseCode != 200) error("GitHub answered ${conn.responseCode}")
            return pick(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    /** From GitHub's list of releases: the highest "vN" tag with an APK, drafts left out, pre-releases in. */
    fun pick(json: String): Release? = JSONArray(json).objects()
        .filter { !it.optBoolean("draft") }
        .mapNotNull { r ->
            val code = TAG.find(r.optString("tag_name"))?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
            val apk = r.optJSONArray("assets")?.objects()?.firstOrNull { it.optString("name").endsWith(".apk") } ?: return@mapNotNull null
            Release(code, r.optString("name").ifBlank { r.optString("tag_name") }, r.optString("body").trim(), apk.getString("browser_download_url"), apk.optLong("size"))
        }
        .maxByOrNull { it.code }

    /**
     * Looks for a newer release. [quiet] (the check at launch): at most every 12 hours, says nothing unless
     * there's one, and not again for a version the user put off. Otherwise (Settings): says how it went.
     */
    fun check(activity: Activity, ui: Ui, quiet: Boolean) {
        val p = prefs(activity)
        // A download that's been installed (or overtaken) isn't needed any more.
        val have = installed(activity).first
        dir(activity).listFiles()?.filter { (it.name.removePrefix("um-timetable-").removeSuffix(".apk").toLongOrNull() ?: 0) <= have }?.forEach { it.delete() }
        if (quiet && System.currentTimeMillis() - p.getLong("updateCheckedAt", 0) < EVERY) return
        if (!quiet) Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show()
        thread {
            val result = runCatching { latest(activity) }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onFailure { e ->
                    Log.w("UMTimetable", "update check failed", e)
                    if (!quiet) Toast.makeText(activity, "Couldn't check for updates: ${e.message}", Toast.LENGTH_LONG).show()
                }
                val release = result.getOrNull()
                if (result.isSuccess) p.edit().putLong("updateCheckedAt", System.currentTimeMillis()).apply()
                val (code, name) = installed(activity)
                when {
                    result.isFailure -> Unit
                    release == null || release.code <= code ->
                        if (!quiet) Toast.makeText(activity, "You're up to date ($name).", Toast.LENGTH_SHORT).show()
                    quiet && p.getLong("updateLater", 0) == release.code -> Unit
                    else -> offer(activity, ui, release)
                }
            }
        }
    }

    /** The update card: what's new, then Later and Download; a progress bar while it downloads; then Install. */
    private fun offer(activity: Activity, ui: Ui, release: Release) {
        val p = ui.p
        var cancelled = false
        val status = ui.text("", 13f, p.muted).apply { visibility = View.GONE }
        val bar = ui.bar(0f, p.accent, p.surface2).apply { visibility = View.GONE }
        val buttons = ui.row(gap = 8f)
        val card = ConfirmDialog.frame(ui).apply {
            addView(ui.text("Update to ${release.name}", 30f, p.text, ui.f.sign))
            val size = if (release.size > 0) "%.1f MB".format(release.size / 1_048_576.0) else null
            addView(ui.text(plain(release.notes), 15f, p.muted, lines = 10).apply { setLineSpacing(0f, 1.35f) }, ui.wide(top = 8f, bottom = 4f))
            size?.let { addView(ui.text(it, 13f, p.faint, ui.f.mono), ui.wide(bottom = 16f)) }
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(4)).apply { bottomMargin = ui.dp(8) })
            addView(status, ui.wide(bottom = 16f))
            addView(buttons)
        }
        lateinit var close: (then: (() -> Unit)?) -> Unit
        close = ConfirmDialog.present(ui, card) { cancelled = true }
        fun show(text: String?) {
            status.text = text
            status.visibility = if (text == null) View.GONE else View.VISIBLE
        }
        fun setButtons(left: String, onLeft: () -> Unit, right: String?, onRight: () -> Unit) {
            buttons.removeAllViews()
            buttons.addView(ConfirmDialog.button(ui, left, p.surface2, p.text, onLeft), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            right?.let { buttons.addView(ConfirmDialog.button(ui, it, p.accent, p.accentInk, onRight), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        fun later() {
            prefs(activity).edit().putLong("updateLater", release.code).apply()
            close(null)
        }
        lateinit var start: () -> Unit
        fun ready(file: File) {
            bar.visibility = View.GONE
            show("Downloaded. Android's installer asks you to confirm.")
            setButtons("Later", ::later, "Install") { install(activity, file, release) }
        }
        start = {
            bar.visibility = View.VISIBLE
            progress(bar, 0f)
            show("Downloading…")
            setButtons("Cancel", { close(null) }, null) {}
            thread {
                val result = runCatching {
                    download(activity, release, { cancelled }) { done -> activity.runOnUiThread { progress(bar, done); show("Downloading… ${(done * 100).toInt()}%") } }
                }
                activity.runOnUiThread {
                    if (cancelled) return@runOnUiThread
                    result.onSuccess(::ready).onFailure { e ->
                        Log.w("UMTimetable", "update download failed", e)
                        bar.visibility = View.GONE
                        show("Couldn't download: ${e.message}")
                        setButtons("Later", ::later, "Try again") { start() }
                    }
                }
            }
        }
        setButtons("Later", ::later, "Download") { start() }
    }

    /**
     * Release notes as the card shows them: GitHub's Markdown without its marks ("**Android 12:**" was
     * shown as written), bullets as "•", headings and blank lines dropped.
     */
    fun plain(markdown: String): String = markdown.lines()
        .map { line ->
            line.trim()
                .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1") // [text](link)
                .replace(Regex("""\*\*|__|`"""), "")
                .replace(Regex("""^[-*+] +"""), "• ")
        }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .joinToString("\n")

    private fun progress(bar: LinearLayout, f: Float) {
        (bar.getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight = f.coerceIn(0f, 1f)
        (bar.getChildAt(1).layoutParams as LinearLayout.LayoutParams).weight = 1 - f.coerceIn(0f, 1f)
        bar.requestLayout()
    }

    private fun dir(c: Context) = File(c.getExternalFilesDir(null) ?: c.filesDir, "updates").apply { mkdirs() }

    /** Downloads the APK into the app's own storage (older downloads go first), telling [onProgress] how far. */
    private fun download(c: Context, release: Release, cancelled: () -> Boolean, onProgress: (Float) -> Unit): File {
        val dir = dir(c)
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "um-timetable-${release.code}.apk")
        val conn = URL(release.url).openConnection() as HttpURLConnection // GitHub redirects to its file host, over HTTPS
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "UM-Timetable/${installed(c).second}")
        try {
            if (conn.responseCode != 200) error("GitHub answered ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            var got = 0L
            var shown = -1
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) error("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        val percent = if (total > 0) (got * 100 / total).toInt() else 0
                        if (percent != shown) { shown = percent; onProgress(percent / 100f) }
                    }
                }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        // It must be this app and the version the release says, or it's not installed.
        val info = c.packageManager.getPackageArchiveInfo(file.path, 0)
        if (info?.packageName != c.packageName || info.longVersionCode != release.code) {
            file.delete()
            error("the download isn't ${release.name} of this app")
        }
        return file
    }

    /**
     * Hands the APK to Android's installer. The first time, Android has to allow this app to install apps:
     * its settings page opens, and Install is tapped again on coming back.
     */
    private fun install(activity: Activity, file: File, release: Release) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Allow 马大牛逼 to install apps, then tap Install again.", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        Log.i("UMTimetable", "update: installing ${release.name} (${release.code})")
        val uri = Uri.parse("content://${activity.packageName}.updates/${file.name}")
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, UpdateProvider.APK).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Lends a downloaded update to Android's installer, read only: just the APKs in the app's updates folder.
 * (The app has no AndroidX, so not FileProvider; this is the part of it the updater needs.)
 */
class UpdateProvider : ContentProvider() {
    private fun file(uri: Uri): File {
        val dir = File(context!!.getExternalFilesDir(null) ?: context!!.filesDir, "updates")
        val file = File(dir, uri.lastPathSegment ?: "")
        if (file.parentFile != dir || !file.name.endsWith(".apk") || !file.isFile) throw FileNotFoundException(uri.toString())
        return file
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = APK
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val APK = "application/vnd.android.package-archive"
    }
}
