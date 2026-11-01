package com.skylightlim.umtimetable

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What's new on a course page since the last sync, from core_course_get_updates_since: an activity that
 * wasn't there before, or new files in one that was. A change to an activity's settings alone isn't news
 * (Spectrum reports many a week, with no way to tell what changed). The file ids in a report tell the
 * same upload apart from a later one, so the overlap between syncs never tells it twice.
 */
object CourseUpdates {
    /** Told about elsewhere: attendance, announcements, and Due's assignments and quizzes. A subsection is only a heading. */
    private val SILENT = setOf("attendance", "forum", "assign", "quiz", "subsection")

    /** How long an activity keeps its New on the course page after the page was first opened. */
    const val SHOWN_AFTER_OPENING = 10 * 60_000L

    /** A changed activity: [added] if it's new to the course, and what identifies this change among those seen before. */
    data class Change(val cmid: Int, val added: Boolean, val keys: List<String>)

    /** The activities in a report (instances of core_course_get_updates_since) that are new, or have new files. */
    fun changes(instances: List<JSONObject>, known: Set<Int>): List<Change> =
        instances.filter { it.optString("contextlevel") == "module" }.mapNotNull { i ->
            val cmid = i.getInt("id")
            val added = cmid !in known
            val files = i.optJSONArray("updates")?.objects().orEmpty().firstOrNull { it.optString("name") == "contentfiles" }
            if (!added && files == null) return@mapNotNull null
            val ids = files?.optJSONArray("itemids")?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty()
            Change(cmid, added, ids.map { "$cmid:$it" } + listOfNotNull("$cmid:new".takeIf { added }, "$cmid:files".takeIf { files != null && ids.isEmpty() }))
        }

    /**
     * What to tell the user of [changes], given the course's [modules] by id (core_course_get_contents):
     * not seen before, open to them and shown on the page, and not named for other occurrences only.
     */
    fun items(changes: List<Change>, modules: Map<Int, JSONObject>, seen: Set<String>, occ: Int): List<JSONObject> =
        changes.filter { c -> c.keys.any { it !in seen } }.mapNotNull { c ->
            val m = modules[c.cmid] ?: return@mapNotNull null
            val name = m.optString("name")
            if (m.optString("modname") in SILENT || !m.optBoolean("uservisible", true) || m.optInt("visibleoncoursepage", 1) == 0 || AttendanceMatch.othersOnly(name, occ)) return@mapNotNull null
            JSONObject().put("cmid", c.cmid).put("name", name).put("added", c.added)
        }

    /** A notification's text: "New: Week 5 slides, Lab 3. Updated: Course outline and 2 more." */
    fun text(items: List<JSONObject>): String {
        fun line(word: String, names: List<String>): String? {
            if (names.isEmpty()) return null
            val more = names.size - 4
            return "$word: ${names.take(4).joinToString(", ") { it.take(60) }}" + if (more > 0) " and $more more" else ""
        }
        val (added, updated) = items.partition { it.optBoolean("added") }
        return listOfNotNull(line("New", added.map { it.optString("name") }), line("Updated", updated.map { it.optString("name") })).joinToString(". ")
    }

    // --- the New marks: {courseId: {cmid: when the course page was first opened after it, or 0}} ---

    /** Marks [cmids] as new in course [courseId]; those opened long enough ago are dropped from [marks]. */
    fun mark(marks: JSONObject, courseId: Int, cmids: List<Int>, now: Long) {
        for (course in marks.keys().asSequence().toList()) {
            val list = marks.getJSONObject(course)
            for (cm in list.keys().asSequence().toList()) if (list.getLong(cm).let { it > 0 && now - it > SHOWN_AFTER_OPENING }) list.remove(cm)
        }
        val list = marks.optJSONObject("$courseId") ?: JSONObject().also { marks.put("$courseId", it) }
        for (cm in cmids) list.put("$cm", 0L)
    }

    /** Course [courseId]'s page is open: the activities still marked New, the time of first opening noted on them. */
    fun open(marks: JSONObject, courseId: Int, now: Long): Set<Int> {
        val list = marks.optJSONObject("$courseId") ?: return emptySet()
        val shown = mutableSetOf<Int>()
        for (cm in list.keys().asSequence().toList()) {
            val at = list.getLong(cm)
            if (at == 0L) list.put(cm, now)
            if (at == 0L || now - at <= SHOWN_AFTER_OPENING) shown += cm.toInt()
        }
        return shown
    }

    /** Whether course [courseId] has something new that its page hasn't been opened for. */
    fun unopened(marks: JSONObject, courseId: Int): Boolean =
        marks.optJSONObject("$courseId")?.let { list -> list.keys().asSequence().any { list.getLong(it) == 0L } } == true

    private const val MARKS = "course-marks"

    private fun marks(c: Context) = runCatching { JSONObject(SpectrumStore.read(c, MARKS) ?: "{}") }.getOrDefault(JSONObject())

    fun marksNow(c: Context): JSONObject = synchronized(this) { marks(c) }

    fun markNew(c: Context, courseId: Int, cmids: List<Int>) = synchronized(this) {
        marks(c).let { mark(it, courseId, cmids, System.currentTimeMillis()); SpectrumStore.write(c, MARKS, it.toString()) }
    }

    /** Puts `fresh` on the modules of an opened [course] page that are marked New, for [SpectrumScreens]. */
    fun openPage(c: Context, courseId: Int, course: JSONObject?) = synchronized(this) {
        val all = marks(c)
        val before = all.toString()
        val shown = open(all, courseId, System.currentTimeMillis())
        if (all.toString() != before) SpectrumStore.write(c, MARKS, all.toString())
        for (s in course?.optJSONArray("sections")?.objects().orEmpty()) {
            for (m in s.optJSONArray("modules")?.objects().orEmpty()) m.put("fresh", m.optInt("cmid") in shown)
        }
    }

    /** The ids in a saved list. */
    fun ints(a: JSONArray?): Set<Int> = a?.let { (0 until it.length()).map(it::getInt).toSet() }.orEmpty()

    /** The strings in a saved list. */
    fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map(it::getString) }.orEmpty()
}
