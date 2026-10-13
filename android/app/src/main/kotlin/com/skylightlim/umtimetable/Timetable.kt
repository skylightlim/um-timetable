package com.skylightlim.umtimetable

import android.content.Context
import android.graphics.Color
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import kotlin.math.ceil

val DAY_NAMES = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
val DAY_SHORT = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

data class Course(val code: String, val name: String, val short: String, val occ: Int, val color: Int)

/** One weekly class. [day] is 1 = Monday … 5 = Friday; [start] and [end] are minutes after midnight. */
data class Session(
    val day: Int,
    val start: Int,
    val end: Int,
    val type: String,
    val room: String,
    val lecturer: String?,
    val course: Course,
    /** MAYA's full room name ("Dewan Kuliah 5 H11") when it differs from [room]. */
    val roomName: String? = null,
) {
    val isTutorial get() = type == "Tutorial"
}

/** A moment in the week: [dow] is 0 = Sunday … 6 = Saturday, [min] is minutes after midnight. */
data class Now(val dow: Int, val min: Float) {
    companion object {
        fun current(): Now {
            val c = Calendar.getInstance()
            return Now(
                c.get(Calendar.DAY_OF_WEEK) - 1,
                c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60f,
            )
        }
    }
}

enum class Phase { IN_CLASS, NEXT_TODAY, NEXT_LATER }

data class Status(
    val phase: Phase,
    /** The class in progress, or the next one to go to. */
    val session: Session,
    /** Minutes until [session] ends (in class) or starts (otherwise). */
    val minutesAway: Int,
    /** How far through the class we are, 0–1. Only meaningful in class. */
    val progress: Float,
    /** For [Phase.NEXT_LATER]: how many days ahead [session] is. */
    val daysAhead: Int,
    val isWeekday: Boolean,
    /** Classes today that haven't finished, including the one in progress. */
    val classesLeftToday: Int,
    val minutesLeftToday: Int,
)

/**
 * [courses] keeps the order of the data's course list. [term] labels
 * the semester the classes belong to, e.g. "Sem 1 2026/27"; the MAYA import sets it.
 */
class Timetable(val sessions: List<Session>, val courses: List<Course> = sessions.map { it.course }.distinct(), val term: String? = null) {

    fun day(d: Int): List<Session> = sessions.filter { it.day == d }.sortedBy { it.start }

    /** Null only when the timetable has no classes at all. */
    fun status(now: Now): Status? {
        val isWeekday = now.dow in 1..5
        val today = if (isWeekday) day(now.dow) else emptyList()
        val left = today.filter { it.end > now.min }
        val minsLeft = ceil(left.sumOf { (it.end - maxOf(it.start.toFloat(), now.min)).toDouble() }).toInt()
        val current = today.firstOrNull { it.start <= now.min && now.min < it.end }
        val next = today.firstOrNull { it.start > now.min }
        return when {
            current != null -> Status(
                Phase.IN_CLASS, current, ceil(current.end - now.min).toInt(),
                (now.min - current.start) / (current.end - current.start), 0, isWeekday, left.size, minsLeft,
            )
            next != null -> Status(
                Phase.NEXT_TODAY, next, ceil(next.start - now.min).toInt(), 0f, 0, isWeekday, left.size, minsLeft,
            )
            else -> {
                // The next weekday that has classes; a free day in between is skipped.
                val ahead = (1..7).firstOrNull { k -> (now.dow + k) % 7 in 1..5 && day((now.dow + k) % 7).isNotEmpty() } ?: return null
                val first = day((now.dow + ahead) % 7).first()
                Status(
                    Phase.NEXT_LATER, first, ceil(ahead * 1440 + first.start - now.min).toInt(),
                    0f, ahead, isWeekday, 0, 0,
                )
            }
        }
    }

    companion object {
        @Volatile private var cached: Timetable? = null
        private const val LOCAL = "timetable.json"

        /** The app's timetable as saved by the MAYA import (and TimeEdit's lecturers), if there is one. */
        fun local(context: Context): String? = File(context.filesDir, LOCAL).takeIf { it.exists() }?.readText()

        fun setLocal(context: Context, json: String?) {
            val file = File(context.filesDir, LOCAL)
            if (json == null) file.delete() else file.writeText(json)
            cached = null
        }

        /** The data behind [load]: the app's timetable, or an empty one before the first import. */
        fun json(context: Context): JSONObject =
            local(context)?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.has("sessions") }
                ?: JSONObject().put("courses", JSONObject()).put("sessions", org.json.JSONArray())

        fun load(context: Context): Timetable = cached ?: runCatching { parse(json(context)) }.getOrElse { Timetable(emptyList()) }.also { cached = it }

        /** `{term, courses: {code: {name, short, occ, color}}, sessions: [{day, start, end, code, type, room, roomName, lecturer}]}` */
        fun parse(root: JSONObject): Timetable {
            val cj = root.getJSONObject("courses")
            val courses = cj.keys().asSequence().associateWith { code ->
                val c = cj.getJSONObject(code)
                Course(code, c.getString("name"), c.optString("short").ifBlank { c.getString("name") }, c.getInt("occ"), Color.parseColor(c.getString("color")))
            }
            val sj = root.getJSONArray("sessions")
            val sessions = (0 until sj.length()).map { i ->
                val s = sj.getJSONObject(i)
                Session(
                    day = s.getInt("day"),
                    start = toMinutes(s.getString("start")),
                    end = toMinutes(s.getString("end")),
                    type = s.getString("type"),
                    room = s.getString("room"),
                    lecturer = if (s.isNull("lecturer")) null else s.getString("lecturer"),
                    course = courses.getValue(s.getString("code")),
                    roomName = s.optString("roomName").takeIf { it.isNotBlank() },
                )
            }
            return Timetable(sessions, courses.values.filter { c -> sessions.any { it.course == c } }, root.optString("term").takeIf { it.isNotBlank() })
        }

        private fun toMinutes(t: String) = t.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
    }
}

fun clockText(min: Int) = "%02d:%02d".format(min / 60, min % 60)

fun durationText(min: Int): String {
    val d = min / 1440
    val h = min % 1440 / 60
    val m = min % 60
    return when {
        d > 0 -> if (h > 0) "$d d $h h" else "$d d"
        h > 0 -> if (m > 0) "$h h $m min" else "$h h"
        else -> "$m min"
    }
}
