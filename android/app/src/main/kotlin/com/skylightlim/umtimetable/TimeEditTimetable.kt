package com.skylightlim.umtimetable

import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's timetable from TimeEdit's answer (assets/timeedit-lookup.js): the weekly classes of the
 * user's course occurrences, with rooms and lecturers, in the same shape as the MAYA import. JSON in,
 * JSON out, so it runs in the JVM tests.
 */
object TimeEditTimetable {

    data class Result(val json: JSONObject, val classes: Int, val courses: Int, val notFound: List<String>, val term: String?)

    /**
     * [answer]: the script's `slots`. [occurrences]: the courses asked for ({"WIX1002": 10}), in
     * Spectrum's order. [spectrumNames]: Spectrum's full course names by code. [current]: the app's
     * timetable, whose course names and colours are kept, and whose lecturer is kept for the very
     * same class when TimeEdit lists none. Null when TimeEdit has no weekly class for any course.
     */
    fun build(answer: JSONObject, occurrences: JSONObject, spectrumNames: Map<String, String>, current: JSONObject): Result? {
        val slots = (answer.optJSONArray("slots") ?: JSONArray()).objects()
        // A class on in two or more of the six weeks is weekly; a one-off (a replacement class) isn't.
        val need = if (slots.any { it.optInt("weeks", 1) >= 2 }) 2 else 1
        val weekly = slots.filter { it.optInt("weeks", 1) >= need && it.optInt("day") in 1..5 }
            .sortedWith(compareBy({ it.optInt("day") }, { it.optString("start") }))
        if (weekly.isEmpty()) return null

        val known = current.optJSONObject("courses") ?: JSONObject()
        val codes = occurrences.keys().asSequence().filter { code -> weekly.any { it.optString("code") == code } }.toList()
        val courses = JSONObject()
        codes.forEachIndexed { i, code ->
            // TimeEdit's own name is Malay ("ASAS-ASAS PENGATURCARAAN"): only when Spectrum has none.
            val module = weekly.first { it.optString("code") == code }.optString("module").takeIf { it.isNotBlank() }?.let(MayaImport::titleCase)
            courses.put(code, MayaImport.course(code, occurrences.getInt(code), i, known.optJSONObject(code), spectrumNames[code], module))
        }

        val keptLecturers = (current.optJSONArray("sessions") ?: JSONArray()).objects()
            .filter { !it.isNull("lecturer") && it.optString("lecturer").isNotBlank() }
            .associate { key(it) to it.getString("lecturer") }
        val sessions = JSONArray()
        for (s in weekly) {
            val room = s.optString("room").trim()
            val session = JSONObject()
                .put("day", s.optInt("day"))
                .put("start", s.optString("start"))
                .put("end", s.optString("end"))
                .put("code", s.optString("code"))
                .put("type", MayaImport.titleCase(s.optString("activity")).ifBlank { "Class" })
                .put("room", MayaImport.shortRoom(room))
                .put("roomName", if (room.isEmpty()) "Online (TimeEdit lists no room)" else MayaImport.roomName(room))
            val lecturers = (s.optJSONArray("lecturers") ?: JSONArray()).objects()
                .map { TimeEditLookup.displayName(it.optString("title"), it.optString("name")) }.filter { it.isNotBlank() }
            session.put("lecturer", lecturers.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: keptLecturers[key(session)] ?: JSONObject.NULL)
            sessions.put(session)
        }

        val term = term(weekly.map { it.optString("term") }) ?: current.optString("term").ifBlank { null }
        val json = JSONObject().put("courses", courses).put("sessions", sessions)
        if (term != null) json.put("term", term)
        return Result(json, weekly.size, codes.size, occurrences.keys().asSequence().filter { it !in codes }.toList(), term)
    }

    /** TimeEdit's "2026/S1" (from the offering "WIX1002/2026/S1/10") → "Sem 1 2026/27". */
    fun term(values: List<String>): String? {
        val raw = values.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val m = Regex("""^(20\d\d)/S(\d)$""", RegexOption.IGNORE_CASE).find(raw.trim()) ?: return null
        val year = m.groupValues[1].toInt()
        return "Sem ${m.groupValues[2]} $year/%02d".format((year + 1) % 100)
    }

    data class Diff(val same: Int, val changed: List<String>, val added: List<String>, val removed: List<String>) {
        val identical get() = changed.isEmpty() && added.isEmpty() && removed.isEmpty()
    }

    /** What [built] changes in [current], class by class: the same day, times, course and type. */
    fun compare(current: JSONObject, built: JSONObject): Diff {
        val old = (current.optJSONArray("sessions") ?: JSONArray()).objects().associateBy(::key)
        val new = built.getJSONArray("sessions").objects().associateBy(::key)
        var same = 0
        val changed = mutableListOf<String>()
        for ((k, n) in new) {
            val o = old[k] ?: continue
            val notes = buildList {
                if (o.optString("room") != n.optString("room")) add("room ${o.optString("room")} → ${n.optString("room")}")
                val was = if (o.isNull("lecturer")) "" else o.optString("lecturer")
                val now = if (n.isNull("lecturer")) "" else n.optString("lecturer")
                if (now.isNotBlank() && !(was.isNotBlank() && TimeEditLookup.samePerson(was, now))) add(if (was.isBlank()) "lecturer $now" else "lecturer $was → $now")
            }
            if (notes.isEmpty()) same++ else changed += "${label(n)}: ${notes.joinToString(", ")}"
        }
        return Diff(same, changed, new.filterKeys { it !in old }.values.map(::label), old.filterKeys { it !in new }.values.map(::label))
    }

    private fun key(s: JSONObject) = "${s.optInt("day")}|${s.optString("start")}|${s.optString("end")}|${s.optString("code")}|${s.optString("type").lowercase()}"
    private fun label(s: JSONObject) = "${s.optString("code")} ${s.optString("type").lowercase()}, ${DAY_SHORT[s.optInt("day")]} ${s.optString("start")}–${s.optString("end")}"
}
