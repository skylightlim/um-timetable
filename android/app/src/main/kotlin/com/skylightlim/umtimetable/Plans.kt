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
 * What the user plans to do in a free stretch of a day, set from the Week page: on one date (not
 * every week), from [start] to [end] (minutes after midnight), in [color], with an optional reminder
 * [remind] minutes before it starts. Past ones can be added too, as a record.
 */
data class Plan(
    val id: String,
    val date: LocalDate,
    val start: Int,
    val end: Int,
    val text: String,
    val color: Int,
    val remind: Int? = null,
) {
    /** When it starts, in epoch milliseconds (the phone's time zone). */
    val startsAt get() = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + start * 60_000L
    val endsAt get() = startsAt + (end - start) * 60_000L

    /** "Plan", when nothing was written. */
    val title get() = text.ifBlank { "Plan" }

    fun json() = JSONObject().put("id", id).put("date", date.toString()).put("start", start).put("end", end)
        .put("text", text).put("color", color).put("remind", remind ?: JSONObject.NULL)

    companion object {
        fun new(date: LocalDate, start: Int, end: Int, color: Int) = Plan(UUID.randomUUID().toString(), date, start, end, "", color)

        /** The Monday of [date]'s week. */
        fun weekOf(date: LocalDate): LocalDate = date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

        fun from(o: JSONObject) = Plan(
            o.getString("id"), LocalDate.parse(o.getString("date")), o.getInt("start"), o.getInt("end"),
            o.optString("text"), o.getInt("color"), if (o.isNull("remind")) null else o.optInt("remind"),
        )

        /** The reminder choices: minutes before the start (null: none). The plan card shows them on a tape. */
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

    fun all(c: Context): List<Plan> = runCatching {
        val f = File(c.filesDir, FILE)
        if (!f.exists()) emptyList() else JSONArray(f.readText()).objects().map(Plan::from)
    }.getOrDefault(emptyList())

    /** The plans from [monday] to that Friday. */
    fun inWeek(c: Context, monday: LocalDate): List<Plan> = all(c).filter { !it.date.isBefore(monday) && it.date.isBefore(monday.plusDays(5)) }

    /** Adds or replaces [plan]; its reminder can go off again (its time may have changed). */
    fun save(c: Context, plan: Plan) {
        SpectrumStore.putStringSet(c, "planReminded", SpectrumStore.stringSet(c, "planReminded") - plan.id)
        write(c, all(c).filter { it.id != plan.id } + plan)
    }

    fun delete(c: Context, id: String) = write(c, all(c).filter { it.id != id })

    private fun write(c: Context, plans: List<Plan>) {
        File(c.filesDir, FILE).writeText(JSONArray(plans.sortedWith(compareBy({ it.date }, { it.start })).map { it.json() }).toString())
        PlanReminders.schedule(c)
    }
}

/** Reminders for plans: one exact alarm at a time, for the next one due; re-armed after a reboot. */
object PlanReminders {
    private fun at(p: Plan) = p.remind?.let { p.startsAt - it * 60_000L }

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = SpectrumAlarmReceiver.intent(c, SpectrumAlarmReceiver.ACTION_PLAN)
        val now = System.currentTimeMillis()
        val fired = SpectrumStore.stringSet(c, "planReminded")
        val next = Plans.all(c).filter { it.id !in fired }.mapNotNull(::at).filter { it > now }.minOrNull()
        if (next == null) am.cancel(pi) else exactAlarm(am, next, pi)
    }

    fun fire(c: Context) {
        val now = System.currentTimeMillis()
        val plans = Plans.all(c)
        val fired = SpectrumStore.stringSet(c, "planReminded")
        // Due now (a minute early is fine), and not after it has ended: a phone asleep past it says nothing.
        for (p in plans) {
            val at = at(p) ?: continue
            if (p.id in fired || at > now + 60_000 || p.endsAt <= now) continue
            val time = "${clockText(p.start)}–${clockText(p.end)}"
            val whenText = when {
                p.startsAt <= now -> "Now · $time"
                p.remind == 0 -> "Starting · $time"
                else -> "In ${Plan.REMINDERS.first { it.first == p.remind }.second} · $time"
            }
            Notify.post(c, Notify.PLANS, "plan-${p.id}", p.title, whenText, Notify.openApp(c, "week"))
            fired += p.id
        }
        SpectrumStore.putStringSet(c, "planReminded", fired.filter { id -> plans.any { it.id == id } }.toSet())
        schedule(c)
    }
}
