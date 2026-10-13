package com.skylightlim.umtimetable

import android.content.Context
import android.text.Html
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Turns MAYA's "Timetable View" page (SITS e:Vision, SIW_XTTB, list layout) into the
 * app's timetable data. MAYA lists dated classes for the weeks on screen; each has a
 * time, occurrence, course code, activity and room, but no course name or lecturer.
 */
object MayaImport {

    data class Event(val date: String, val start: String, val end: String, val code: String, val activity: String, val occ: Int, val room: String, val semester: String = "")

    private val DAY_ROW = Regex("""data-jqtt-date="(\d{8})\d*"(.*?)(?=data-jqtt-date="|\z)""", RegexOption.DOT_MATCHES_ALL)
    private val ITEM = Regex("""<li class="sitsjqtteventcontainer[^"]*"[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
    private val FIELD = Regex("""(Occurrence|Code|Semester|Activity|Room):\s*([^\n]+)""")
    private val TIME = Regex("""(\d\d:\d\d)\s*-\s*(\d\d:\d\d)""")

    fun events(html: String): List<Event> = DAY_ROW.findAll(html).flatMap { row ->
        ITEM.findAll(row.groupValues[2]).mapNotNull { li ->
            val text = li.groupValues[1].replace(Regex("<[^>]+>"), "\n")
            val f = FIELD.findAll(text).associate { it.groupValues[1] to decode(it.groupValues[2]) }
            val time = TIME.find(text) ?: return@mapNotNull null
            val code = f["Code"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Event(row.groupValues[1], time.groupValues[1], time.groupValues[2], code, f["Activity"].orEmpty(), f["Occurrence"]?.toIntOrNull() ?: 0, f["Room"].orEmpty(), f["Semester"].orEmpty())
        }
    }.distinct().toList()

    data class Result(
        val json: JSONObject,
        val classes: Int,
        val courses: Int,
        val weeks: Int,
        val firstDate: String,
        val lastDate: String,
        /** Classes that got their lecturer from the app's current timetable. */
        val lecturersKept: Int,
        /** "Sem 1 2026/27", from [term]. */
        val term: String,
    )

    /**
     * The weekly pattern behind the dated events. With two or more weeks on screen, a class has
     * to appear in at least two of them, so a one-off replacement class isn't taken as weekly.
     */
    fun timetable(context: Context, events: List<Event>): Result? {
        if (events.isEmpty()) return null
        val weeks = events.map { mondayOf(it.date) }.toSet().size
        Log.i("UMTimetable", "MAYA Semester lines: ${events.map { it.semester }.distinct()}")
        val need = if (weeks >= 2) 2 else 1
        data class Slot(val day: Int, val start: String, val end: String, val code: String, val activity: String, val occ: Int, val room: String)
        val slots = events.groupingBy { Slot(dayOfWeek(it.date), it.start, it.end, it.code, it.activity, it.occ, it.room) }.eachCount()
            .filter { (slot, n) -> n >= need && slot.day in 1..5 }.keys
            .sortedWith(compareBy({ it.day }, { it.start }))
        if (slots.isEmpty()) return null

        // A re-import keeps what the app already has: course names, short names and colours for known
        // courses (else Spectrum's name and the next palette colour), and, as MAYA has no lecturers,
        // the lecturer of the very same class (day, start, end, course, type).
        val current = runCatching { Timetable.local(context)?.let { JSONObject(it) } }.getOrNull() ?: JSONObject()
        val knownCourses = current.optJSONObject("courses") ?: JSONObject()
        val classKey = { day: Int, start: String, end: String, code: String, type: String -> "$day|$start|$end|$code|${type.lowercase()}" }
        val lecturers = mutableMapOf<String, String>()
        for (s in (current.optJSONArray("sessions") ?: JSONArray()).objects()) {
            if (s.isNull("lecturer") || s.optString("lecturer").isBlank()) continue
            lecturers[classKey(s.optInt("day"), s.optString("start"), s.optString("end"), s.optString("code"), s.optString("type"))] = s.getString("lecturer")
        }
        val spectrumNames = SpectrumStore.array(context, "courses").objects().associate { it.optString("code") to it.optString("name") }
        val courses = JSONObject()
        slots.map { it.code }.distinct().forEachIndexed { i, code ->
            val occ = slots.filter { it.code == code }.groupingBy { it.occ }.eachCount().maxBy { it.value }.key
            courses.put(code, course(code, occ, i, knownCourses.optJSONObject(code), spectrumNames[code]))
        }
        var kept = 0
        val sessions = JSONArray(
            slots.map { s ->
                val type = titleCase(s.activity).ifBlank { "Class" }
                val lecturer = lecturers[classKey(s.day, s.start, s.end, s.code, type)]
                if (lecturer != null) kept++
                JSONObject()
                    .put("day", s.day).put("start", s.start).put("end", s.end).put("code", s.code)
                    .put("type", type)
                    .put("room", shortRoom(s.room))
                    .put("roomName", roomName(s.room))
                    .put("lecturer", lecturer ?: JSONObject.NULL)
            },
        )
        val dates = events.map { it.date }.sorted()
        val term = term(events)
        return Result(JSONObject().put("term", term).put("courses", courses).put("sessions", sessions), slots.size, courses.length(), weeks, dates.first(), dates.last(), kept, term)
    }

    /**
     * One course of the timetable data (MAYA and TimeEdit imports): what the app already has for [code]
     * ([known]: name, short name, colour), else Spectrum's name ([spectrum], its full name), else
     * [fallback], and the [index]th palette colour.
     */
    fun course(code: String, occ: Int, index: Int, known: JSONObject?, spectrum: String?, fallback: String? = null): JSONObject {
        val name = known?.optString("name")?.takeIf { it.isNotBlank() && it != code }
            ?: spectrum?.let { spectrumName(code, it) }
            ?: fallback?.takeIf { it.isNotBlank() }
            ?: code
        return JSONObject()
            .put("name", name)
            .put("short", known?.optString("short")?.takeIf { it.isNotBlank() && it != code } ?: name)
            .put("occ", occ)
            .put("color", known?.optString("color")?.takeIf { it.isNotBlank() } ?: PALETTE[index % PALETTE.size])
    }

    /** Spectrum's course name without its code: "WIX1001 COMPUTING MATHEMATICS I" → "Computing Mathematics I". */
    fun spectrumName(code: String, fullname: String): String? =
        titleCase(fullname.replace(Regex("""^\s*${Regex.escape(code)}\b[\s:|–-]*""", RegexOption.IGNORE_CASE), "")).takeIf { it.isNotBlank() }

    /**
     * Names the courses the timetable only knows by code, from Spectrum's course list: a MAYA import
     * before Spectrum's first sync has no names. Runs on every sync; saves only when it named one.
     */
    fun fillNames(context: Context, spectrumCourses: JSONArray) {
        val json = Timetable.local(context)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val courses = json.optJSONObject("courses") ?: return
        val names = spectrumCourses.objects().associate { it.optString("code") to it.optString("name") }
        var named = 0
        for (code in courses.keys()) {
            val course = courses.getJSONObject(code)
            if (course.optString("name").let { it.isNotBlank() && it != code }) continue
            val name = names[code]?.let { spectrumName(code, it) } ?: continue
            course.put("name", name)
            if (course.optString("short").let { it.isBlank() || it == code }) course.put("short", name)
            named++
        }
        if (named == 0) return
        Timetable.setLocal(context, json.toString())
        Log.i("UMTimetable", "named $named courses from Spectrum")
    }

    /**
     * "Sem 1 2026/27", the label above the timetable. MAYA puts a Semester line on every class, but
     * its wording hasn't been seen yet (the import logs it), so this accepts "S1", "SEM 1",
     * "Semester 1" or a bare "1", with or without a year ("2026/2027", "2026/S1"). Whatever the line
     * lacks comes from the first class date: Semester 1 runs about October to February, Semester 2
     * about March to July (a special semester in August is labelled Sem 2 this way).
     */
    fun term(events: List<Event>): String {
        val raw = events.map { it.semester }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        val first = events.minOf { it.date }
        val year = first.substring(0, 4).toInt()
        val month = first.substring(4, 6).toInt()
        val sem = when {
            Regex("""(?i)khas|special""").containsMatchIn(raw) -> "Special Sem"
            else -> (Regex("""(?i)(?:^|[^a-z])(?:s|sem|semester)\s*[-:]?\s*0?([1-3])(?!\d)""").find(raw) ?: Regex("""^\s*0?([1-3])\s*$""").find(raw))
                ?.let { "Sem ${it.groupValues[1]}" }
                ?: if (month in 3..8) "Sem 2" else "Sem 1"
        }
        val start = Regex("""20\d\d""").find(raw)?.value?.toInt() ?: if (month >= 9) year else year - 1
        return "$sem $start/%02d".format((start + 1) % 100)
    }

    /**
     * "Dewan Kuliah 2 FSKTM (280)" → DK2, "MAKMAL MIKRO 7 FSKTM" → MM7, "Dewan Kuliah 3 H09" → DK3 H09,
     * TimeEdit's "MAKMAL GABUNG (MAKMAL MIKRO 8)" → MM8 (the room in brackets). MAYA gives online
     * classes no room ("N/A") and TimeEdit calls them "ONLINE LECTURE": both show as Online.
     */
    fun shortRoom(raw: String): String {
        val r = raw.replace(Regex("""\s*\(\d+\)\s*$"""), "").trim()
        if (r.isEmpty() || r.equals("N/A", ignoreCase = true) || ONLINE.matches(r)) return "Online"
        Regex("""\(([^)]+)\)""").find(r)?.let { m -> known(m.groupValues[1].trim())?.let { return it } }
        known(r)?.let { return it }
        if (r.length <= 10) return r
        return r.split(Regex("\\s+")).joinToString("") { w -> if (w.any(Char::isDigit) || w.length <= 2) w else w.take(1).uppercase() }
    }

    private val ONLINE = Regex("""(?i)online( lecture| class| tutorial)?""")

    private fun known(r: String): String? {
        for ((pattern, prefix) in listOf("dewan kuliah" to "DK", "makmal mikro" to "MM", "bilik tutorial" to "BT", "bilik seminar" to "BS")) {
            Regex("""(?i)^$pattern\s*(\d+)\s*(.*)$""").find(r)?.let { m ->
                val rest = m.groupValues[2].trim().takeUnless { it.isEmpty() || it.equals("FSKTM", ignoreCase = true) }
                return "$prefix${m.groupValues[1]}" + (rest?.let { " $it" } ?: "")
            }
        }
        return null
    }

    /** The full room name, tidied: capacity dropped, SHOUTING turned into title case. */
    fun roomName(raw: String): String {
        val r = raw.replace(Regex("""\s*\(\d+\)\s*$"""), "").trim()
        if (r.isEmpty() || r.equals("N/A", ignoreCase = true)) return "Online (MAYA lists no room)"
        return r.split(Regex("\\s+")).joinToString(" ") { w ->
            if (w.any(Char::isDigit) || w.trim('(', ')').uppercase() in ABBREVIATIONS) w.uppercase()
            else w.lowercase().let { l -> l.indexOfFirst(Char::isLetter).let { i -> if (i < 0) l else l.substring(0, i) + l[i].uppercase() + l.substring(i + 1) } }
        }
    }

    private val ABBREVIATIONS = setOf("FSKTM", "UM", "UMLEADER")
    private val SMALL = setOf("and", "of", "the", "for", "in", "to", "a", "an", "on", "at")
    fun titleCase(s: String): String = s.trim().lowercase().split(Regex("\\s+")).mapIndexed { i, w ->
        if (i > 0 && w in SMALL) w
        // UM's own names: EMBRACE@UMLEADER → Embrace@UMLeader.
        else w.split('@').mapIndexed { j, part ->
            if (j > 0 && part.length > 2 && part.startsWith("um")) "UM" + part.drop(2).replaceFirstChar { it.uppercase() }
            else part.replaceFirstChar { it.uppercase() }
        }.joinToString("@")
    }.joinToString(" ").replace(Regex("""\bI\b|\bIi\b|\bIii\b|\bUm\b""")) { it.value.uppercase() }

    private fun decode(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun calendar(date: String) = Calendar.getInstance().apply {
        clear()
        set(date.substring(0, 4).toInt(), date.substring(4, 6).toInt() - 1, date.substring(6, 8).toInt())
    }

    private fun dayOfWeek(date: String) = calendar(date).get(Calendar.DAY_OF_WEEK) - 1

    private fun mondayOf(date: String): Long = calendar(date).apply {
        add(Calendar.DAY_OF_MONTH, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
    }.timeInMillis

    private val PALETTE = listOf("#a586e8", "#4f9cf0", "#f0913c", "#2ec4a0", "#e97bc0", "#f2645f", "#a6cf4f", "#5cc8e0", "#d9a54a")
}
