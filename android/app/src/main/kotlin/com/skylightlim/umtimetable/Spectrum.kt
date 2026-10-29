package com.skylightlim.umtimetable

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Spectrum is UM's Moodle. The app signs in the way the official Moodle app does
 * (tool_mobile launch, "moodle_mobile_app" service) and calls Moodle's REST web
 * services with the user's own token. Nothing goes anywhere except spectrum.um.edu.my.
 */
object Spectrum {
    const val SITE = "https://spectrum.um.edu.my"
    const val SERVICE = "moodle_mobile_app"

    class Error(val code: String, message: String) : Exception(message)

    /** Calls one web-service function. Returns a JSONObject or JSONArray; throws [Error] on a Moodle error. */
    fun call(token: String, function: String, params: Map<String, Any?> = emptyMap()): Any {
        val body = StringBuilder()
        fun add(name: String, value: String) {
            if (body.isNotEmpty()) body.append('&')
            body.append(URLEncoder.encode(name, "UTF-8")).append('=').append(URLEncoder.encode(value, "UTF-8"))
        }
        // Moodle's array syntax: courseids[0]=12, options[timestart]=…
        fun flatten(name: String, value: Any?) {
            when (value) {
                null -> Unit
                is Map<*, *> -> value.forEach { (k, v) -> flatten("$name[$k]", v) }
                is List<*> -> value.forEachIndexed { i, v -> flatten("$name[$i]", v) }
                is Boolean -> add(name, if (value) "1" else "0")
                else -> add(name, value.toString())
            }
        }
        add("wstoken", token)
        add("wsfunction", function)
        add("moodlewsrestformat", "json")
        params.forEach { (k, v) -> flatten(k, v) }

        val conn = URL("$SITE/webservice/rest/server.php?moodlewsrestformat=json&wsfunction=$function")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: throw Error("http", "Spectrum answered HTTP ${conn.responseCode}")
            val value = JSONTokener(text).nextValue()
            if (value is JSONObject && value.has("exception")) {
                throw Error(value.optString("errorcode", "error"), value.optString("message", "Spectrum error"))
            }
            return value
        } finally {
            conn.disconnect()
        }
    }

    /** Pulls a course code like WIX1002 out of a Spectrum course name. */
    fun courseCode(vararg names: String?): String? = names.firstNotNullOfOrNull { n ->
        n?.let { Regex("""\b([A-Z]{3,4})\s?(\d{4})\b""").find(it) }?.let { it.groupValues[1] + it.groupValues[2] }
    }
}

/** Where Spectrum data lives on the phone: the token in [SecureStore], everything else in private app storage. */
object SpectrumStore {
    private fun prefs(c: Context) = c.getSharedPreferences("spectrum", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.noBackupFilesDir, "spectrum").apply { mkdirs() }

    fun token(c: Context): String? = SecureStore.get(c, "token") { noteSignOut(c, "the phone could no longer open the saved sign-in") }
    fun signedIn(c: Context) = SecureStore.has(c, "token")
    fun userId(c: Context) = prefs(c).getInt("userid", 0)
    fun name(c: Context): String = prefs(c).getString("fullname", "") ?: ""

    fun saveLogin(c: Context, token: String, siteInfo: JSONObject) {
        SecureStore.put(c, "token", token)
        prefs(c).edit().clear()
            .putInt("userid", siteInfo.getInt("userid"))
            .putString("fullname", siteInfo.optString("fullname"))
            .apply()
    }

    // Why the sign-in last ended, kept apart from what signing out deletes (the user wondered whether
    // sign-outs were their own taps or a bug).
    private fun account(c: Context) = c.getSharedPreferences("account", Context.MODE_PRIVATE)

    fun noteSignOut(c: Context, why: String) {
        account(c).edit().putLong("signedOutAt", System.currentTimeMillis()).putString("signedOutWhy", why).apply()
        android.util.Log.i("UMTimetable", "signed out: $why")
    }

    /** "today 16:03: you tapped Sign out", or null if the app has never been signed out. */
    fun lastSignOut(c: Context): String? {
        val at = account(c).getLong("signedOutAt", 0).takeIf { it > 0 } ?: return null
        return "${dayWord(at, System.currentTimeMillis())} ${hm(at)}: ${account(c).getString("signedOutWhy", "")}"
    }

    fun signOut(c: Context) {
        SecureStore.put(c, "token", null)
        prefs(c).edit().clear().apply()
        dir(c).deleteRecursively()
        arrays.clear()
    }

    fun read(c: Context, name: String): String? = File(dir(c), "$name.json").takeIf { it.exists() }?.readText()

    /**
     * The user's occurrence in each course ({"WIX1002": 10}): from their Spectrum groups at the last
     * sync, except where they chose another in Settings ([occurrencePicks]).
     */
    fun occurrences(c: Context): JSONObject = spectrumOccurrences(c).also { o -> occurrencePicks(c).let { p -> for (k in p.keys()) o.put(k, p.getInt(k)) } }

    /** The occurrences of the user's Spectrum groups alone, at the last sync. */
    fun spectrumOccurrences(c: Context): JSONObject = json(c, "occurrences")

    /** The occurrences the user chose in Settings over Spectrum's ({"WIX1002": 3}). */
    fun occurrencePicks(c: Context): JSONObject = json(c, "occurrence-picks")

    /** Chooses [occ] for [code]; Spectrum's own (or null) takes the choice away. */
    fun pickOccurrence(c: Context, code: String, occ: Int?) {
        val picks = occurrencePicks(c)
        if (occ == null || occ == spectrumOccurrences(c).optInt(code)) picks.remove(code) else picks.put(code, occ)
        write(c, "occurrence-picks", picks.toString())
    }

    /** Each course's occurrences on TimeEdit ({"WIX1002": [1, 2, 16]}), from the last timetable lookup. */
    fun occurrenceOptions(c: Context): JSONObject = json(c, "occurrence-options")

    /** The sections of course [courseId] the user pinned to the top of its page, in the order pinned. */
    fun pinnedSections(c: Context, courseId: Int): List<String> =
        json(c, "pinned-sections").optJSONArray(courseId.toString())?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

    /** Pins section [key] of course [courseId] (after those pinned already), or unpins it. */
    fun pinSection(c: Context, courseId: Int, key: String, pin: Boolean) {
        val all = json(c, "pinned-sections")
        val now = pinnedSections(c, courseId).filter { it != key } + listOfNotNull(key.takeIf { pin })
        if (now.isEmpty()) all.remove(courseId.toString()) else all.put(courseId.toString(), JSONArray(now))
        write(c, "pinned-sections", all.toString())
    }

    private fun json(c: Context, name: String) = read(c, name)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    fun write(c: Context, name: String, json: String) {
        // A sync still running when the user signs out mustn't put back what Sign out deleted.
        if (!signedIn(c)) return
        File(dir(c), "$name.json").writeText(json)
        arrays.remove(name)
    }

    /** Parsed files, reused until the file changes: every redraw reads news, due and attendance. Read-only for callers. */
    private val arrays = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, JSONArray>>()
    fun array(c: Context, name: String): JSONArray {
        val file = File(dir(c), "$name.json")
        val stamp = file.lastModified()
        arrays[name]?.takeIf { it.first == stamp }?.let { return it.second }
        val parsed = runCatching { JSONArray(if (stamp == 0L) "[]" else file.readText()) }.getOrDefault(JSONArray())
        arrays[name] = stamp to parsed
        return parsed
    }

    /** What's due, less what the user dismissed (a long press in Due): every page, reminder and the widget read this. */
    fun due(c: Context): List<JSONObject> = dismissedDue(c).let { gone -> array(c, "due").objects().filter { DueList.activityKey(it) !in gone } }

    /** The activities the user dismissed from Due ([DueList.activityKey]). */
    fun dismissedDue(c: Context): Set<String> = stringSet(c, "dismissedDue")

    /** Dismisses [item] from Due, or ([gone] false) brings it back. */
    fun dismissDue(c: Context, item: JSONObject, gone: Boolean) {
        val key = DueList.activityKey(item)
        val set = stringSet(c, "dismissedDue").apply { remove(key) }
        // Only those still listed are kept, plus this one: a long-gone deadline needn't be remembered.
        val listed = array(c, "due").objects().map(DueList::activityKey).toSet()
        putStringSet(c, "dismissedDue", set.filter { it in listed }.toSet() + listOfNotNull(key.takeIf { gone }))
    }

    fun lastSync(c: Context) = prefs(c).getLong("lastSync", 0)
    fun lastError(c: Context): String? = prefs(c).getString("lastError", null)
    fun recordSync(c: Context, error: String?) {
        val e = prefs(c).edit().putString("lastError", error)
        if (error == null) e.putLong("lastSync", System.currentTimeMillis())
        e.apply()
    }

    fun stringSet(c: Context, name: String): MutableSet<String> = (prefs(c).getStringSet(name, emptySet()) ?: emptySet()).toMutableSet()
    fun putStringSet(c: Context, name: String, value: Set<String>) = prefs(c).edit().putStringSet(name, value).apply()
    fun flag(c: Context, name: String) = prefs(c).getBoolean(name, false)
    fun long(c: Context, name: String) = prefs(c).getLong(name, 0)
    fun putLong(c: Context, name: String, v: Long) = prefs(c).edit().putLong(name, v).apply()
    fun setFlag(c: Context, name: String, v: Boolean) = prefs(c).edit().putBoolean(name, v).apply()
    fun cmid(c: Context, attendanceId: Int) = prefs(c).getInt("cmid-$attendanceId", 0)
    fun putCmid(c: Context, attendanceId: Int, cmid: Int) = prefs(c).edit().putInt("cmid-$attendanceId", cmid).apply()

    data class WidgetAlert(val text: String, val live: Boolean, val url: String?)

    /** One line for the widget: an open attendance session, else the next deadline this week. */
    fun widgetAlert(c: Context, now: Long): WidgetAlert? {
        if (!signedIn(c)) return null
        array(c, "attendance").objects().firstOrNull { it.getLong("start") <= now && now < it.getLong("end") && it.optBoolean("listed", true) }?.let { a ->
            return WidgetAlert("Attendance open · ${a.optString("code", a.optString("course"))} · until ${hm(a.getLong("end"))}", true, a.optString("url"))
        }
        val next = due(c).filter { it.getLong("due") - now in 1 until 7 * 86_400_000L }.minByOrNull { it.getLong("due") }
        return next?.let { WidgetAlert("Due ${dayWord(it.getLong("due"), now)} ${hm(it.getLong("due"))} · ${it.optString("name")}", false, null) }
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.UK)
    private fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

    fun hm(ms: Long): String = HM.format(local(ms))

    fun dayWord(ms: Long, now: Long): String {
        return when (Math.round((startOfDay(ms) - startOfDay(now)) / 86_400_000.0)) {
            0L -> "today"
            1L -> "tomorrow"
            else -> WEEKDAY.format(local(ms))
        }
    }

}
