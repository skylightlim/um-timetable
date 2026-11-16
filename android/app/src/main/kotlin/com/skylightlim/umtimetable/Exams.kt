package com.skylightlim.umtimetable

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One of the user's exams, as MAYA's timetable lists it: on [date] from [start] to [end] (minutes after
 * midnight), course [code] (occurrence [occ]) in [room] (MAYA's full name). [flags] are the exam lines MAYA
 * fills in only for some students ("Barred from Exam: Yes"); [early] is how many minutes before the start MAYA
 * asks them to be there (0 when it doesn't say).
 */
data class Exam(
    val date: LocalDate,
    val start: Int,
    val end: Int,
    val code: String,
    val occ: Int,
    val room: String,
    val flags: List<String> = emptyList(),
    val early: Int = 0,
) {
    val endsAt get() = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + end * 60_000L

    /** The room for a card's room column: "Dewan 4" for the exam building's halls, else as a class's room. */
    val shortRoom: String get() = HALL.find(room)?.let { "Dewan ${it.groupValues[1]}" } ?: MayaImport.shortRoom(room)

    /** "Exam · be there by 08:45", or what MAYA flagged instead. */
    val note: String get() = flags.joinToString(" · ").ifEmpty { if (early > 0) "be there by ${clockText(start - early)}" else "" }

    fun json() = JSONObject().put("date", date.toString()).put("start", start).put("end", end).put("code", code)
        .put("occ", occ).put("room", room).put("flags", JSONArray(flags)).put("early", early)

    companion object {
        /** "Dewan 4 Aras 2 Bangunan Peperiksaan": hall 4 on level 2 of the exam building. */
        private val HALL = Regex("""(?i)^\s*dewan\s+(\d+)\b.*peperiksaan""")

        fun from(o: JSONObject) = Exam(
            LocalDate.parse(o.getString("date")), o.getInt("start"), o.getInt("end"), o.getString("code"),
            o.optInt("occ"), o.optString("room"), CourseUpdates.strings(o.optJSONArray("flags")), o.optInt("early"),
        )
    }
}

/**
 * The exams, read from MAYA's timetable ([read]) and kept in the app's files (so Sign out deletes them with the
 * rest). MAYA lists them among the classes of My Timetable (SIW_XTTB) as "Centrally Scheduled Exam" events,
 * once UM has set the exam timetable: see docs/FINDINGS.md.
 */
object Exams {
    private const val FILE = "exams.json"

    fun all(c: Context): List<Exam> = runCatching {
        val f = File(c.filesDir, FILE)
        if (!f.exists()) emptyList() else JSONArray(f.readText()).objects().map(Exam::from)
    }.getOrDefault(emptyList())

    fun on(c: Context, day: LocalDate): List<Exam> = all(c).filter { it.date == day }

    /** The exams from [monday] to that Friday. */
    fun inWeek(c: Context, monday: LocalDate): List<Exam> = all(c).filter { it.date >= monday && it.date < monday.plusDays(5) }

    /** The exams from today on, in order. */
    fun ahead(c: Context): List<Exam> = LocalDate.now().let { today -> all(c).filter { it.date >= today } }

    /** Saves [found], read from MAYA's timetable over the dates [days]: they take the place of the saved exams on those dates. */
    fun replace(c: Context, days: ClosedRange<LocalDate>, found: List<Exam>) {
        val kept = all(c).filter { it.date !in days }
        File(c.filesDir, FILE).writeText(JSONArray((kept + found).sortedWith(compareBy({ it.date }, { it.start })).map { it.json() }).toString())
    }

    /** MAYA's timetable form takes dates as "01/Feb/2027". */
    fun mayaDate(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("dd/MMM/yyyy", Locale.ENGLISH))

    private val DAY_ROW = Regex("""data-jqtt-date="(\d{8})\d*"(.*?)(?=data-jqtt-date="|\z)""", RegexOption.DOT_MATCHES_ALL)
    private val ITEM = Regex("""<li class="sitsjqtteventcontainer[^"]*"[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
    private val TIME = Regex("""(\d\d):(\d\d)\s*-\s*(\d\d):(\d\d)""")
    /**
     * A "Label: value" line, with the tags taken out. MAYA's own spelling and markup vary by line: an exam's
     * "Module:" and "Occurence:" where a class has "Code:" and "Occurrence:", the colon sometimes after the
     * closing tag ("Semester</b>:") and the value sometimes on the next line ("Room: </b>Dewan…").
     */
    private val FIELD = Regex("""(Module|Code|Occurr?ence|Activity|Room|Barred from Exam|Quarantine Exam|Special Case)[ \t]*\n?[ \t]*:[ \t]*\n?[ \t]*([^\n]*)""")
    private val EXAM = Regex("""(?i)exam|peperiksaan""")
    private val EARLY = Regex("""(?i)at least (\d+) minutes? before""")
    private val NOTHING = Regex("""(?i)^(n/?a|-|no|none|tiada|tidak)?$""")

    /** The dates MAYA's timetable page shows, first to last; null when it shows none. */
    fun days(html: String): ClosedRange<LocalDate>? {
        val dates = DAY_ROW.findAll(html).map { date(it.groupValues[1]) }.toList()
        return if (dates.isEmpty()) null else dates.min()..dates.max()
    }

    /** The exams on MAYA's timetable page (SIW_XTTB, any layout with dated day rows): its events whose activity is an exam. */
    fun read(html: String): List<Exam> = DAY_ROW.findAll(html).flatMap { row ->
        ITEM.findAll(row.groupValues[2]).mapNotNull { li ->
            val text = decode(li.groupValues[1].replace(Regex("<[^>]+>"), "\n"))
            val f = FIELD.findAll(text).associate { it.groupValues[1] to it.groupValues[2].trim() }
            if (!EXAM.containsMatchIn(f["Activity"].orEmpty())) return@mapNotNull null
            val t = TIME.find(text) ?: return@mapNotNull null
            val code = (f["Module"] ?: f["Code"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Exam(
                date(row.groupValues[1]),
                t.groupValues[1].toInt() * 60 + t.groupValues[2].toInt(),
                t.groupValues[3].toInt() * 60 + t.groupValues[4].toInt(),
                code,
                (f["Occurence"] ?: f["Occurrence"])?.toIntOrNull() ?: 0,
                f["Room"].orEmpty(),
                listOf("Barred from Exam", "Quarantine Exam", "Special Case").mapNotNull { k -> f[k]?.takeUnless { NOTHING.matches(it) }?.let { "$k: $it" } },
                EARLY.find(text)?.groupValues?.get(1)?.toInt() ?: 0,
            )
        }
    }.distinct().toList()

    private fun date(yyyymmdd: String) = LocalDate.of(yyyymmdd.substring(0, 4).toInt(), yyyymmdd.substring(4, 6).toInt(), yyyymmdd.substring(6, 8).toInt())

    /** The few entities MAYA's text uses, and numbered ones: android.text.Html isn't there in the JVM tests. */
    private fun decode(s: String) = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("""&#(\d+);""")) { it.groupValues[1].toInt().toChar().toString() }
}
