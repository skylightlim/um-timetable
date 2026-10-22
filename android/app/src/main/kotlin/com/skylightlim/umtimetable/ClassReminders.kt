package com.skylightlim.umtimetable

import android.app.AlarmManager
import android.content.Context
import java.time.LocalDate

/**
 * Settings' "Class reminders": a notification some minutes before each class the user goes to (the
 * classes Day shows), with its time and room. None on a public holiday or in a week without classes.
 * Off until chosen. One exact alarm at a time, as for plans ([PlanReminders]).
 */
object ClassReminders {
    /** The choices, in minutes before a class; null is off. */
    val CHOICES = listOf<Pair<Int?, String>>(
        null to "Off", 0 to "At start", 5 to "5 min before", 10 to "10 min before", 15 to "15 min before",
        30 to "30 min before", 60 to "1 h before",
    )
    private const val KEY = "classReminder"
    private const val FIRED = "classReminded"

    /** Minutes before each class, or null when they're off. */
    fun before(c: Context): Int? = uiPrefs(c).getInt(KEY, -1).takeIf { it >= 0 }

    fun setBefore(c: Context, minutes: Int?) {
        uiPrefs(c).edit().putInt(KEY, minutes ?: -1).apply()
        schedule(c)
    }

    fun label(c: Context): String = CHOICES.firstOrNull { it.first == before(c) }?.second ?: "Off"

    private class Due(val at: Long, val start: Long, val end: Long, val key: String, val session: Session)

    /** Each class's reminder from today to a week ahead, [before] minutes ahead of it. */
    private fun ahead(c: Context, before: Int): List<Due> {
        val timetable = Timetable.load(c)
        return (0..7L).flatMap { k ->
            val date = LocalDate.now().plusDays(k)
            if (Holidays.on(date) != null || weekOff(date) != null) return@flatMap emptyList()
            val midnight = startOfDay(date)
            timetable.day(date.dayOfWeek.value % 7).map { s ->
                val start = midnight + s.start * 60_000L
                Due(start - before * 60_000L, start, midnight + s.end * 60_000L, "$date ${s.start} ${s.course.code}", s)
            }
        }
    }

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = SpectrumAlarmReceiver.intent(c, SpectrumAlarmReceiver.ACTION_CLASS)
        val before = before(c) ?: return am.cancel(pi)
        val now = System.currentTimeMillis()
        val fired = uiPrefs(c).getStringSet(FIRED, emptySet()).orEmpty()
        val next = ahead(c, before).filter { it.key !in fired && it.at > now }.minOfOrNull { it.at }
        exactAlarm(am, next, pi)
    }

    fun fire(c: Context) {
        val before = before(c) ?: return
        val now = System.currentTimeMillis()
        val due = ahead(c, before)
        val fired = uiPrefs(c).getStringSet(FIRED, emptySet()).orEmpty().toMutableSet()
        // Due now (a minute early is fine), and not once the class is over: a phone asleep past it says nothing.
        for (d in due) {
            if (d.key in fired || d.at > now + 60_000 || d.end <= now) continue
            val s = d.session
            val time = "${clockText(s.start)}–${clockText(s.end)}"
            // The time left as it is now: a phone that slept through the alarm says less than chosen.
            val whenText = when {
                d.start <= now -> "Now"
                before == 0 -> "Starting"
                else -> "In ${durationText(((d.start - now + 59_999) / 60_000).toInt())}"
            }
            Notify.post(
                c, Notify.CLASSES, "class-${d.key}",
                "${s.course.short} · ${s.type}",
                listOf(whenText, time, s.roomName ?: s.room).filter { it.isNotBlank() }.joinToString(" · "),
                Notify.openApp(c, "day"),
            )
            fired += d.key
        }
        uiPrefs(c).edit().putStringSet(FIRED, fired.filter { key -> due.any { it.key == key } }.toSet()).apply()
        schedule(c)
    }
}
