package com.skylightlim.umtimetable

import org.json.JSONObject

/**
 * Rules for the Due list, kept apart from the sync so they can be tested on the JVM.
 *
 * Spectrum's calendar can list one activity more than once: a quiz or lesson has an event when it opens
 * and one when it closes, an activity with "Expect completed on" has that date's event besides its due
 * date, and a group override adds another. Some courses also post the same assignment once per
 * occurrence ("Assignment 1 (OCC 3)", "Assignment 1 (OCC 4)"), each open to its own group. [distinct]
 * keeps one row per activity; [forMe] drops those named for other occurrences only.
 */
object DueList {
    /**
     * The event types that are an activity's deadline, best first: an assignment's due date, a quiz's or
     * lesson's close. "Expect completed on" is only the lecturer's suggestion, so it's chosen last.
     */
    private val DEADLINE = listOf("due", "close", "deadline")
    private const val SUGGESTED = "expectcompletionon"

    /** The activity a row is for: its module and instance, or (without them) its event id. */
    fun activityKey(item: JSONObject): String {
        val module = item.optString("module")
        val instance = item.optInt("instance")
        return if (module.isNotEmpty() && instance > 0) "${item.optInt("courseId")}/$module/$instance" else "event/${item.optInt("id")}"
    }

    /**
     * One row per activity: of its events, the deadline (due or close) over the others, and of those, the
     * next still to come, else the last passed. Then rows of the same course with the same name and due
     * time (the same activity added twice) are one. The order is kept.
     */
    fun distinct(items: List<JSONObject>, now: Long = System.currentTimeMillis()): List<JSONObject> {
        fun rank(e: JSONObject) = when (val t = e.optString("eventtype")) {
            in DEADLINE -> DEADLINE.indexOf(t)
            SUGGESTED -> DEADLINE.size + 1
            else -> DEADLINE.size
        }
        val chosen = items.groupBy(::activityKey).mapValues { (_, events) ->
            val best = events.minOf(::rank)
            val (coming, passed) = events.filter { rank(it) == best }.partition { it.optLong("due") > now }
            coming.minByOrNull { it.optLong("due") } ?: passed.maxBy { it.optLong("due") }
        }
        val seen = HashSet<String>()
        return items.filter { chosen[activityKey(it)] === it }
            .filter { seen.add("${it.optInt("courseId")}\u0000${it.optString("name").trim().lowercase()}\u0000${it.optLong("due")}") }
    }

    /** Leaves out activities named for other occurrences only, given the user's occurrence in each course ([occOf], 0 unknown). */
    fun forMe(items: List<JSONObject>, occOf: (String) -> Int): List<JSONObject> =
        items.filter { !AttendanceMatch.othersOnly(it.optString("name"), occOf(it.optString("code"))) }
}
