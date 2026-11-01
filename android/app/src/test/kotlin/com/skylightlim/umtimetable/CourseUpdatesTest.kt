package com.skylightlim.umtimetable

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A course page's news: new activities and new files, told once, and only what's for this student. */
class CourseUpdatesTest {
    private fun instance(id: Int, vararg areas: Pair<String, List<Long>>, level: String = "module") = JSONObject().put("contextlevel", level).put("id", id)
        .put("updates", JSONArray(areas.map { (name, ids) -> JSONObject().put("name", name).put("itemids", JSONArray(ids)) }))

    private fun module(name: String, modname: String = "resource", visible: Boolean = true) =
        JSONObject().put("name", name).put("modname", modname).put("uservisible", visible)

    @Test fun settingsChangesAloneAreNotNews() {
        val changes = CourseUpdates.changes(listOf(instance(1, "configuration" to emptyList())), known = setOf(1))
        assertEquals(emptyList<CourseUpdates.Change>(), changes)
    }

    @Test fun newFilesInAKnownActivityAreNews() {
        val change = CourseUpdates.changes(listOf(instance(1, "configuration" to emptyList(), "contentfiles" to listOf(77L))), known = setOf(1)).single()
        assertFalse(change.added)
        assertEquals(listOf("1:77"), change.keys)
    }

    @Test fun anActivityNotSeenBeforeIsNew() {
        val change = CourseUpdates.changes(listOf(instance(2, "configuration" to emptyList())), known = setOf(1)).single()
        assertTrue(change.added)
        assertEquals(listOf("2:new"), change.keys)
    }

    @Test fun onlyActivitiesCount() {
        assertEquals(emptyList<CourseUpdates.Change>(), CourseUpdates.changes(listOf(instance(9, "contentfiles" to listOf(1L), level = "course")), known = emptySet()))
    }

    @Test fun theSameUploadIsToldOnceAndALaterOneAgain() {
        val modules = mapOf(1 to module("Week 5 slides"))
        val first = CourseUpdates.changes(listOf(instance(1, "contentfiles" to listOf(77L))), known = setOf(1))
        assertEquals(1, CourseUpdates.items(first, modules, seen = emptySet(), occ = 0).size)
        assertEquals(0, CourseUpdates.items(first, modules, seen = setOf("1:77"), occ = 0).size)
        val later = CourseUpdates.changes(listOf(instance(1, "contentfiles" to listOf(77L, 78L))), known = setOf(1))
        assertEquals(1, CourseUpdates.items(later, modules, seen = setOf("1:77"), occ = 0).size)
    }

    @Test fun leavesOutWhatIsToldElsewhereOrNotForThisStudent() {
        val modules = mapOf(
            1 to module("Attendance", "attendance"),
            2 to module("Announcements", "forum"),
            3 to module("Quiz 1", "quiz"),
            4 to module("Another group's slides", visible = false),
            5 to module("Tutorial OCC 7 notes"),
            6 to module("Tutorial OCC 4 notes"),
            7 to module("Week 5 slides"),
            8 to module("Only by its link").put("visibleoncoursepage", 0),
        )
        val changes = modules.keys.map { CourseUpdates.Change(it, true, listOf("$it:new")) }
        assertEquals(listOf(6, 7), CourseUpdates.items(changes, modules, emptySet(), occ = 4).map { it.getInt("cmid") })
    }

    @Test fun aChangeToAnActivityThatIsGoneIsLeftOut() {
        val changes = listOf(CourseUpdates.Change(1, false, listOf("1:77")))
        assertEquals(0, CourseUpdates.items(changes, emptyMap(), emptySet(), occ = 0).size)
    }

    @Test fun textSaysWhatIsNewAndWhatChanged() {
        fun item(name: String, added: Boolean) = JSONObject().put("name", name).put("added", added)
        assertEquals("New: Lab 3, Lab 4. Updated: Outline", CourseUpdates.text(listOf(item("Lab 3", true), item("Outline", false), item("Lab 4", true))))
        assertEquals("New: A, B, C, D and 2 more", CourseUpdates.text("ABCDEF".map { item(it.toString(), true) }))
    }

    @Test fun markStaysUntilThePageIsOpenedThenFades() {
        val marks = JSONObject()
        CourseUpdates.mark(marks, 10, listOf(1), now = 1_000)
        assertTrue(CourseUpdates.unopened(marks, 10))

        val opened = 5_000L
        assertEquals(setOf(1), CourseUpdates.open(marks, 10, opened))
        assertFalse(CourseUpdates.unopened(marks, 10))

        // A later draw of the page keeps it, for a while after the first opening.
        assertEquals(setOf(1), CourseUpdates.open(marks, 10, opened + CourseUpdates.SHOWN_AFTER_OPENING))
        assertEquals(emptySet<Int>(), CourseUpdates.open(marks, 10, opened + CourseUpdates.SHOWN_AFTER_OPENING + 1))
    }

    @Test fun anOldMarkIsDroppedAndAFreshUpdateMarksAgain() {
        val marks = JSONObject()
        CourseUpdates.mark(marks, 10, listOf(1, 2), now = 0)
        CourseUpdates.open(marks, 10, 1_000)
        CourseUpdates.mark(marks, 10, listOf(2), now = 1_000 + CourseUpdates.SHOWN_AFTER_OPENING + 1)
        assertFalse(marks.getJSONObject("10").has("1"))
        assertTrue(CourseUpdates.unopened(marks, 10))
    }
}
