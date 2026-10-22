package com.skylightlim.umtimetable

import android.app.AlarmManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * What the user plans to do in a free stretch of a day, set from the Week page: on [date], from [start]
 * to [end] (minutes after midnight), in [color], with an optional reminder [remind] minutes before it
 * starts. Past ones can be added too, as a record.
 *
 * A [weekly] plan repeats on its weekday every week from [from] to the end of that semester (or to
 * [until], when the user stopped it), but in the weeks they deleted alone ([skip]). It's saved once, dated
 * [from]; [Plans.on] and [Plans.inWeek] give one copy per week, each dated its own day.
 */
data class Plan(
    val id: String,
    val date: LocalDate,
    val start: Int,
    val end: Int,
    val text: String,
    val color: Int,
    val remind: Int? = null,
    val weekly: Boolean = false,
    val from: LocalDate = date,
    val until: LocalDate? = null,
    val skip: Set<LocalDate> = emptySet(),
) {
    /** When it starts, in epoch milliseconds (the phone's time zone). */
    val startsAt get() = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + start * 60_000L
    val endsAt get() = startsAt + (end - start) * 60_000L

    /** "Plan", when nothing was written. */
    val title get() = text.ifBlank { "Plan" }

    /** A repeating plan's last day: the end of [from]'s semester, or [until]. */
    val last: LocalDate? get() = if (!weekly) null else until ?: AcademicCalendar.semesterEnd(from) ?: from.plusWeeks(13)

    /** This copy of a weekly plan repeats after it (it isn't the last week). */
    val repeatsOn get() = weekly && last?.let { date.plusWeeks(1) <= it } == true

    /** Its key among the reminders already given: a weekly plan's says which week. */
    val reminderKey get() = if (weekly) "$id@$date" else id

    /** As saved: dated [from], the first week. */
    fun json() = JSONObject().put("id", id).put("date", from.toString()).put("start", start).put("end", end)
        .put("text", text).put("color", color).put("remind", remind ?: JSONObject.NULL)
        .apply {
            if (!weekly) return@apply
            put("weekly", true)
            until?.let { put("until", it.toString()) }
            if (skip.isNotEmpty()) put("skip", org.json.JSONArray(skip.sorted().map { it.toString() }))
        }

    /** The copy of this weekly plan for [day] (its own weekday), or null when there's none that day. */
    fun on(day: LocalDate): Plan? {
        if (!weekly) return takeIf { day == date }
        val end = last ?: return null
        if (day.dayOfWeek != from.dayOfWeek || day < from || day > end || day in skip) return null
        return copy(date = day)
    }

    companion object {
        fun new(date: LocalDate, start: Int, end: Int, color: Int) = Plan(UUID.randomUUID().toString(), date, start, end, "", color)

        /** The Monday of [date]'s week. */
        fun weekOf(date: LocalDate): LocalDate = date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

        fun from(o: JSONObject) = LocalDate.parse(o.getString("date")).let { date ->
            Plan(
                o.getString("id"), date, o.getInt("start"), o.getInt("end"),
                o.optString("text"), o.getInt("color"), if (o.isNull("remind")) null else o.optInt("remind"),
                weekly = o.optBoolean("weekly"),
                until = o.optString("until").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                skip = o.optJSONArray("skip")?.let { a -> (0 until a.length()).map { LocalDate.parse(a.getString(it)) }.toSet() }.orEmpty(),
            )
        }

        /** The reminder choices: minutes before the start (null: none). The plan card shows them on a wheel. */
        val REMINDERS = listOf<Pair<Int?, String>>(
            null to "Off", 0 to "At start", 5 to "5 min", 10 to "10 min", 15 to "15 min", 30 to "30 min",
            60 to "1 h", 120 to "2 h", 1440 to "1 day",
        )

        /** The lengths a plan can have, in minutes, up to [most]: every half hour ("30 min", "1 h", "1.5 h"…). */
        fun lengths(most: Int): List<Pair<Int, String>> =
            (30..most step 30).map { it to if (it < 60) "$it min" else if (it % 60 == 0) "${it / 60} h" else "${it / 60}.5 h" }
    }
}

/** The plans, kept in the app's files (so Sign out deletes them with the rest). */
object Plans {
    private const val FILE = "plans.json"

    /** The colours a plan can be: the theme's course colours, then its accent. */
    fun palette(scheme: Scheme, accent: Int) = scheme.courseColors.take(7) + accent

    /** The plans as saved: a weekly one once, dated its first week. */
    fun all(c: Context): List<Plan> = runCatching {
        val f = File(c.filesDir, FILE)
        if (!f.exists()) emptyList() else JSONArray(f.readText()).objects().map(Plan::from)
    }.getOrDefault(emptyList())

    /** The plans on [day], weekly ones' copies included. */
    fun on(c: Context, day: LocalDate): List<Plan> = on(all(c), day)

    fun on(plans: List<Plan>, day: LocalDate): List<Plan> = plans.mapNotNull { it.on(day) }

    /** The plans from [monday] to that Friday. */
    fun inWeek(c: Context, monday: LocalDate): List<Plan> = all(c).let { plans -> (0L until 5L).flatMap { on(plans, monday.plusDays(it)) } }

    /**
     * Saves [plan], a new one or a copy of a saved one, edited. A weekly plan's copy changes every week:
     * moved to another weekday, the whole plan moves, its first week and skipped weeks with it. Its
     * reminders can go off again (its time may have changed).
     */
    fun save(c: Context, plan: Plan) {
        SpectrumStore.putStringSet(c, "planReminded", SpectrumStore.stringSet(c, "planReminded").filterNot { it == plan.id || it.startsWith("${plan.id}@") }.toSet())
        val saved = if (!plan.weekly) plan.copy(from = plan.date, until = null, skip = emptySet()) else {
            val shift = (plan.date.dayOfWeek.value - plan.from.dayOfWeek.value).toLong()
            plan.copy(date = plan.from.plusDays(shift), from = plan.from.plusDays(shift), until = plan.until?.plusDays(shift), skip = plan.skip.map { it.plusDays(shift) }.toSet())
        }
        write(c, all(c).filter { it.id != plan.id } + saved)
    }

    fun delete(c: Context, id: String) = write(c, all(c).filter { it.id != id })

    /** Deletes one week of weekly [plan], the copy dated [day]: the others stay. */
    fun skip(c: Context, plan: Plan, day: LocalDate) =
        write(c, all(c).map { if (it.id == plan.id) it.copy(skip = it.skip + day) else it })

    private fun write(c: Context, plans: List<Plan>) {
        File(c.filesDir, FILE).writeText(JSONArray(plans.sortedWith(compareBy({ it.from }, { it.start })).map { it.json() }).toString())
        PlanReminders.schedule(c)
    }
}

/** Reminders for plans: one exact alarm at a time, for the next one due; re-armed after a reboot. */
object PlanReminders {
    private fun at(p: Plan) = p.remind?.let { p.startsAt - it * 60_000L }

    /**
     * The plans from yesterday on, each week of a weekly one apart. Yesterday, for one about to start
     * after midnight with its reminder before; a weekly plan's copies run to the end of its semester.
     */
    private fun ahead(c: Context): List<Plan> {
        val today = LocalDate.now()
        return Plans.all(c).flatMap { p ->
            if (!p.weekly) listOf(p).filter { !it.date.isBefore(today.minusDays(1)) }
            else generateSequence(p.from) { it.plusWeeks(1) }.takeWhile { it <= (p.last ?: it) && it < today.plusYears(1) }
                .filter { !it.isBefore(today.minusDays(1)) }.mapNotNull(p::on).toList()
        }
    }

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = SpectrumAlarmReceiver.intent(c, SpectrumAlarmReceiver.ACTION_PLAN)
        val now = System.currentTimeMillis()
        val fired = SpectrumStore.stringSet(c, "planReminded")
        val next = ahead(c).filter { it.reminderKey !in fired }.mapNotNull(::at).filter { it > now }.minOrNull()
        if (next == null) am.cancel(pi) else exactAlarm(am, next, pi)
    }

    fun fire(c: Context) {
        val now = System.currentTimeMillis()
        val plans = ahead(c)
        val fired = SpectrumStore.stringSet(c, "planReminded")
        // Due now (a minute early is fine), and not after it has ended: a phone asleep past it says nothing.
        for (p in plans) {
            val at = at(p) ?: continue
            if (p.reminderKey in fired || at > now + 60_000 || p.endsAt <= now) continue
            val time = "${clockText(p.start)}–${clockText(p.end)}"
            val whenText = when {
                p.startsAt <= now -> "Now · $time"
                p.remind == 0 -> "Starting · $time"
                else -> "In ${Plan.REMINDERS.first { it.first == p.remind }.second} · $time"
            }
            Notify.post(c, Notify.PLANS, "plan-${p.id}", p.title, whenText, Notify.openApp(c, "week"))
            fired += p.reminderKey
        }
        SpectrumStore.putStringSet(c, "planReminded", fired.filter { key -> plans.any { it.reminderKey == key } }.toSet())
        schedule(c)
    }
}
