package com.skylightlim.umtimetable

import org.json.JSONObject

/**
 * Rules for the Due list, kept apart from the sync so they can be tested on the JVM.
 *
 * Spectrum's calendar can list one activity more than once: a quiz or lesson has an event when it opens
 * and one when it closes, an activity with "Expect completed on" has that date's event besides its due
 * date, and a group override adds another. Some courses also post the same assignment once per
 * occurrence ("Assignment 1 (OCC 3)", "Assignment 1 (OCC 4)"), each open to its own group. [distinct]
 * keeps one row per activity, and per task posted again under the same name; [forMe] drops those named
 * for other occurrences only.
 */
object DueList {
    /**
     * The event types that are an activity's deadline, best first: an assignment's due date, a quiz's or
     * lesson's close. "Expect completed on" is only the lecturer's suggestion, so it's chosen last.
     */
    private val DEADLINE = listOf("due", "close", "deadline")
    private const val SUGGESTED = "expectcompletionon"
    private const val DAY = 86_400_000L

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
        val rows = items.filter { chosen[activityKey(it)] === it }
            .filter { seen.add("${it.optInt("courseId")}\u0000${it.optString("name").trim().lowercase()}\u0000${it.optLong("due")}") }
        // Of each group's copy of one task, the earliest stays: a later row hidden is safer than an earlier one.
        val kept = ArrayList<JSONObject>()
        for (row in rows.sortedBy { it.optLong("due") }) if (kept.none { copies(it, row) }) kept += row
        return rows.filter { row -> kept.any { it === row } }
    }

    /**
     * Lecturers of one course sometimes each post a task for their own groups, at their own time on the
     * same day and with the name retyped ("Assessment 1" and "Assessement 1"). Two rows are such copies
     * when they're in one course, due within a day, and named the same but for at most a typo in one long
     * word. "Quiz 1" and "Quiz 2", or "Part A" and "Part B", aren't.
     */
    private fun copies(a: JSONObject, b: JSONObject): Boolean {
        if (a.optInt("courseId") != b.optInt("courseId") || kotlin.math.abs(a.optLong("due") - b.optLong("due")) >= DAY) return false
        val x = words(a.optString("name"))
        val y = words(b.optString("name"))
        if (x.size != y.size) return false
        val differ = x.indices.filter { x[it] != y[it] }
        return differ.isEmpty() || differ.size == 1 && differ[0].let { typo(x[it], y[it]) }
    }

    private fun words(name: String) = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Two long words of letters, starting alike, at most two edits apart. */
    private fun typo(a: String, b: String): Boolean {
        if (minOf(a.length, b.length) < 5 || a[0] != b[0] || !a.all(Char::isLetter) || !b.all(Char::isLetter)) return false
        var row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val next = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) next[j] = minOf(row[j] + 1, next[j - 1] + 1, row[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            row = next
        }
        return row[b.length] <= 2
    }

    /** Leaves out activities named for other occurrences only, given the user's occurrence in each course ([occOf], 0 unknown). */
    fun forMe(items: List<JSONObject>, occOf: (String) -> Int): List<JSONObject> =
        items.filter { !AttendanceMatch.othersOnly(it.optString("name"), occOf(it.optString("code"))) }
}
