package com.skylightlim.umtimetable

import android.content.Context
import org.json.JSONObject

/**
 * The user's own changes to their classes, made with the class sheet's pen ([ClassSheet]): a course's
 * name and colour, and a class's day, time, type, room and lecturer. Kept apart from the timetable, as
 * [ShortForms] are, so getting it again from TimeEdit doesn't lose them. A class is known by what TimeEdit
 * gave it ([key]): one TimeEdit later moves is a new class, and the change made to the old one stays behind.
 *
 * A class can also be deleted ([GONE], from its card on Week: [ClassCard]): it stays gone until the user
 * gets the timetable from TimeEdit again ([bringBack]), as the card says.
 *
 * `{courses: {CODE: {name?, slot?}}, classes: {KEY: {day?, start?, end?, type?, room?, lecturer?, gone?}}}`; a
 * course's colour is its slot in the theme's course colours, so it changes with the theme as the rest do.
 */
object ClassEdits {
    private const val KEY = "classEdits"

    /** The course fields: the name, and the colour's slot ([Scheme.courseColors]). */
    const val NAME = "name"
    const val SLOT = "slot"
    /** The class fields: day (1 Monday … 5 Friday), start and end (minutes after midnight), type, room, lecturer. */
    const val DAY = "day"
    const val START = "start"
    const val END = "end"
    const val TYPE = "type"
    const val ROOM = "room"
    const val LECTURER = "lecturer"
    /** The class deleted: true. */
    const val GONE = "gone"

    /** A class as TimeEdit gave it: its course, day, start and type ("WIA2003|2|600|Lecture"). */
    fun key(s: Session): String = (s.origin ?: s).let { "${it.course.code}|${it.day}|${it.start}|${it.type}" }

    fun all(c: Context): JSONObject = runCatching { JSONObject(uiPrefs(c).getString(KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())

    /** Course [code]'s [field] ([NAME], [SLOT]) set to [value]. */
    fun setCourse(c: Context, code: String, field: String, value: Any) = edit(c, "courses", code, field, value)

    /** The class [key]'s [field] ([DAY], [START] …) set to [value]. */
    fun setClass(c: Context, key: String, field: String, value: Any) = edit(c, "classes", key, field, value)

    /** Deletes the class [key], every week. */
    fun delete(c: Context, key: String) = setClass(c, key, GONE, true)

    /** The deleted classes, by [key]. */
    fun deleted(c: Context): List<String> = all(c).optJSONObject("classes")?.let { list ->
        list.keys().asSequence().filter { list.optJSONObject(it)?.optBoolean(GONE) == true }.toList()
    }.orEmpty()

    /** Every deleted class back (the timetable got from TimeEdit again), with any other change made to it. */
    fun bringBack(c: Context) {
        val all = all(c)
        if (bringBack(all)) save(c, all)
    }

    /** [all]'s deleted classes back, their other changes kept; false if none was deleted. */
    fun bringBack(all: JSONObject): Boolean {
        val list = all.optJSONObject("classes") ?: return false
        val gone = list.keys().asSequence().filter { list.optJSONObject(it)?.optBoolean(GONE) == true }.toList()
        for (key in gone) {
            val one = list.getJSONObject(key)
            one.remove(GONE)
            if (one.length() == 0) list.remove(key)
        }
        return gone.isNotEmpty()
    }

    /** A deleted class as the TimeEdit dialog lists it: "WIX1001 lecture, Mon 08:00". */
    fun label(key: String): String = key.split("|").let { k ->
        if (k.size < 4) key else "${k[0]} ${k[3].lowercase()}, ${k[1].toIntOrNull()?.let { DAY_SHORT.getOrNull(it) } ?: k[1]} ${k[2].toIntOrNull()?.let(::clockText) ?: k[2]}"
    }

    /** Whether course [code] (or the class [key], when given) has a change of the user's. */
    fun has(c: Context, code: String, key: String?): Boolean = all(c).let { all ->
        all.optJSONObject("courses")?.has(code) == true || (key != null && all.optJSONObject("classes")?.has(key) == true)
    }

    /** Course [code]'s changes, and class [key]'s, taken away: TimeEdit's (and the import's colour) again. */
    fun reset(c: Context, code: String, key: String?) {
        val all = all(c)
        all.optJSONObject("courses")?.remove(code)
        if (key != null) all.optJSONObject("classes")?.remove(key)
        save(c, all)
    }

    private fun edit(c: Context, group: String, id: String, field: String, value: Any) {
        val all = all(c)
        val list = all.optJSONObject(group) ?: JSONObject().also { all.put(group, it) }
        val one = list.optJSONObject(id) ?: JSONObject().also { list.put(id, it) }
        one.put(field, value)
        save(c, all)
    }

    private fun save(c: Context, all: JSONObject) {
        uiPrefs(c).edit().putString(KEY, all.toString()).apply()
        Timetable.forget()
    }

    /** After a change: what's drawn from the timetable outside the app's page (the widget) and what's timed by it (alarms). */
    fun changed(c: Context) {
        TimetableWidget.updateAll(c)
        ClassReminders.schedule(c)
        AutoAttendance.schedule(c)
        AttendanceWatch.schedule(c)
    }
}
