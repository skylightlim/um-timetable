package com.skylightlim.umtimetable

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What's new on a course page since the last sync, from core_course_get_updates_since: an activity that
 * wasn't there before, or new files in one that was. A change to an activity's settings alone isn't news
 * (Spectrum reports many a week, with no way to tell what changed). The file ids in a report tell the
 * same upload apart from a later one, so the overlap between syncs never tells it twice.
 *
 * And what the page says: lecturers put important things in a section's text (General's, above all) or
 * a text block rather than in Announcements, and Spectrum's report lists activities, not a section's
 * text, and says only "configuration" of a text block. So each sync keeps the page's text ([pageTexts])
 * and tells what was added or changed since the last ([told]).
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
            JSONObject().put("cmid", c.cmid).put("mark", "${c.cmid}").put("name", name).put("added", c.added)
        }

    /** One text on a course page: a section's ("s<section id>") or an activity's ("m<cmid>"), and where it is, to name it by. */
    data class Text(val key: String, val place: String, val text: String)

    /**
     * The texts of a course page as core_course_get_contents gives it, made plain by [plain]: each section's
     * summary, and each text block's or activity's description shown on the page. Not what this student
     * can't see, nor what's for another occurrence (by its name, as [items]). A text block is named by its
     * section ("Week 3"), another activity by its own name.
     */
    fun pageTexts(sections: List<JSONObject>, occ: Int, plain: (String) -> String): List<Text> = sections.flatMap { s ->
        if (!s.optBoolean("uservisible", true) || s.optInt("visible", 1) == 0) return@flatMap emptyList()
        val place = plain(s.optString("name")).ifBlank { if (s.optInt("section") == 0) "General" else "Section ${s.optInt("section")}" }
        listOf(Text("s${s.getInt("id")}", place, plain(s.optString("summary")))) +
            s.optJSONArray("modules")?.objects().orEmpty().filter { m ->
                // A folder's description is its list of files, which the file updates already tell.
                m.optString("modname") != "folder" && m.optBoolean("uservisible", true) && m.optInt("visibleoncoursepage", 1) != 0 &&
                    !AttendanceMatch.othersOnly(plain(m.optString("name")), occ)
            }.map { m -> Text("m${m.getInt("id")}", if (m.optString("modname") == "label") place else plain(m.optString("name")), plain(m.optString("description"))) }
    }.filter { it.text.isNotBlank() }

    /**
     * What to tell of [now] given the last sync's texts ([before], by key): each text added or changed, with
     * what it says now that it didn't ([changedPart]). A new activity's text isn't, as the activity is told
     * already ([items]), unless the activity was [known] before; a text only cut shorter isn't either.
     */
    fun told(before: JSONObject, now: List<Text>, known: Set<Int>): List<JSONObject> = now.mapNotNull { t ->
        val module = t.key.startsWith("m")
        if (module && !before.has(t.key) && t.key.drop(1).toInt() !in known) return@mapNotNull null
        val said = changedPart(before.optString(t.key), t.text).ifEmpty { return@mapNotNull null }
        JSONObject().put("mark", if (module) t.key.drop(1) else t.key).put("name", t.place).put("said", said)
    }

    /** Longest a quote from a changed text in a notification. */
    private const val SAID_MAX = 160

    /**
     * What [now] says that [before] didn't: the stretch between what they share at the start and at the end,
     * widened to whole sentences so it reads on its own, at most [SAID_MAX] long. Empty if nothing was added.
     */
    fun changedPart(before: String, now: String): String {
        val start = before.commonPrefixWith(now).length
        val end = now.length - before.substring(start).commonSuffixWith(now.substring(start)).length
        if (end <= start) return ""
        val from = now.lastIndexOfAny(STOPS, start - 1) + 1
        val to = now.indexOfAny(STOPS, end - 1).let { if (it < 0) now.length else it + 1 }
        val said = now.substring(from, to).trim()
        return if (said.length <= SAID_MAX) said else said.take(SAID_MAX - 1).trimEnd() + "…"
    }

    private val STOPS = charArrayOf('.', '!', '?', '\n')

    /** A notification's text: "New: Week 5 slides, Lab 3. Updated: Course outline and 2 more", then a line for each changed text: "General: “Quiz 2 is now on Friday.”" */
    fun text(items: List<JSONObject>): String {
        fun line(word: String, names: List<String>): String? {
            if (names.isEmpty()) return null
            val more = names.size - 4
            return "$word: ${names.take(4).joinToString(", ") { it.take(60) }}" + if (more > 0) " and $more more" else ""
        }
        val (texts, activities) = items.partition { it.has("said") }
        val (added, updated) = activities.partition { it.optBoolean("added") }
        val quotes = texts.take(3).map { "${it.optString("name").take(60)}: “${it.optString("said")}”" } +
            listOfNotNull((texts.size - 3).takeIf { it > 0 }?.let { "And $it more changed texts" })
        val files = listOfNotNull(line("New", added.map { it.optString("name") }), line("Updated", updated.map { it.optString("name") })).joinToString(". ")
        return (listOf(files).filter { it.isNotEmpty() } + quotes).joinToString("\n")
    }

    // --- the New marks: {courseId: {cmid, or "s<section id>" for a section's text: when the course page was first opened after it, or 0}} ---

    /** Marks [keys] (cmids, or "s<section id>") as new in course [courseId]; those opened long enough ago are dropped from [marks]. */
    fun mark(marks: JSONObject, courseId: Int, keys: List<String>, now: Long) {
        for (course in marks.keys().asSequence().toList()) {
            val list = marks.getJSONObject(course)
            for (cm in list.keys().asSequence().toList()) if (list.getLong(cm).let { it > 0 && now - it > SHOWN_AFTER_OPENING }) list.remove(cm)
        }
        val list = marks.optJSONObject("$courseId") ?: JSONObject().also { marks.put("$courseId", it) }
        for (key in keys) list.put(key, 0L)
    }

    /** Course [courseId]'s page is open: what's still marked New, the time of first opening noted on it. */
    fun open(marks: JSONObject, courseId: Int, now: Long): Set<String> {
        val list = marks.optJSONObject("$courseId") ?: return emptySet()
        val shown = mutableSetOf<String>()
        for (key in list.keys().asSequence().toList()) {
            val at = list.getLong(key)
            if (at == 0L) list.put(key, now)
            if (at == 0L || now - at <= SHOWN_AFTER_OPENING) shown += key
        }
        return shown
    }

    /** Whether course [courseId] has something new that its page hasn't been opened for. */
    fun unopened(marks: JSONObject, courseId: Int): Boolean =
        marks.optJSONObject("$courseId")?.let { list -> list.keys().asSequence().any { list.getLong(it) == 0L } } == true

    private const val MARKS = "course-marks"

    private fun marks(c: Context) = SpectrumStore.json(c, MARKS)

    fun marksNow(c: Context): JSONObject = synchronized(this) { marks(c) }

    fun markNew(c: Context, courseId: Int, keys: List<String>) = synchronized(this) {
        marks(c).let { mark(it, courseId, keys, System.currentTimeMillis()); SpectrumStore.write(c, MARKS, it.toString()) }
    }

    /** Puts `fresh` on the sections and modules of an opened [course] page that are marked New, for [SpectrumScreens]. */
    fun openPage(c: Context, courseId: Int, course: JSONObject?) = synchronized(this) {
        val all = marks(c)
        val before = all.toString()
        val shown = open(all, courseId, System.currentTimeMillis())
        if (all.toString() != before) SpectrumStore.write(c, MARKS, all.toString())
        for (s in course?.optJSONArray("sections")?.objects().orEmpty()) {
            s.put("fresh", "s${s.optInt("id")}" in shown)
            for (m in s.optJSONArray("modules")?.objects().orEmpty()) m.put("fresh", "${m.optInt("cmid")}" in shown)
        }
    }

    /** The ids in a saved list. */
    fun ints(a: JSONArray?): Set<Int> = a?.let { (0 until it.length()).map(it::getInt).toSet() }.orEmpty()

    /** The strings in a saved list. */
    fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map(it::getString) }.orEmpty()
}
