package com.skylightlim.umtimetable

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A course page's news: new activities and new files, and texts added or changed, told once, and only what's for this student. */
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
        CourseUpdates.mark(marks, 10, listOf("1"), now = 1_000)
        assertTrue(CourseUpdates.unopened(marks, 10))

        val opened = 5_000L
        assertEquals(setOf("1"), CourseUpdates.open(marks, 10, opened))
        assertFalse(CourseUpdates.unopened(marks, 10))

        // A later draw of the page keeps it, for a while after the first opening.
        assertEquals(setOf("1"), CourseUpdates.open(marks, 10, opened + CourseUpdates.SHOWN_AFTER_OPENING))
        assertEquals(emptySet<String>(), CourseUpdates.open(marks, 10, opened + CourseUpdates.SHOWN_AFTER_OPENING + 1))
    }

    @Test fun anOldMarkIsDroppedAndAFreshUpdateMarksAgain() {
        val marks = JSONObject()
        CourseUpdates.mark(marks, 10, listOf("1", "s5"), now = 0)
        CourseUpdates.open(marks, 10, 1_000)
        CourseUpdates.mark(marks, 10, listOf("s5"), now = 1_000 + CourseUpdates.SHOWN_AFTER_OPENING + 1)
        assertFalse(marks.getJSONObject("10").has("1"))
        assertTrue(CourseUpdates.unopened(marks, 10))
    }

    // --- what the page says ---

    private val plain: (String) -> String = { it.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim() }

    private fun section(id: Int, number: Int, name: String, summary: String, vararg modules: JSONObject, visible: Boolean = true) =
        JSONObject().put("id", id).put("section", number).put("name", name).put("summary", summary).put("uservisible", visible).put("modules", JSONArray(modules.toList()))

    private fun activity(id: Int, modname: String, name: String, description: String) =
        JSONObject().put("id", id).put("modname", modname).put("name", name).put("description", description)

    @Test fun theChangedPartIsItsWholeSentence() {
        assertEquals("Quiz 2 is on Friday at 2pm.", CourseUpdates.changedPart("Welcome. Quiz 2 is on Monday at 2pm. Bring a pen.", "Welcome. Quiz 2 is on Friday at 2pm. Bring a pen."))
        assertEquals("Test 1 moves to DK2.", CourseUpdates.changedPart("Welcome.", "Welcome. Test 1 moves to DK2."))
        assertEquals("", CourseUpdates.changedPart("Welcome. Bring a pen.", "Welcome."))
        assertEquals("", CourseUpdates.changedPart("Same.", "Same."))
        val long = CourseUpdates.changedPart("", "x".repeat(400))
        assertEquals(160, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test fun pageTextsAreWhatTheStudentSeesOnThePage() {
        val texts = CourseUpdates.pageTexts(listOf(
            section(1, 0, "", "<p>Read <b>this</b> first.</p>", activity(11, "label", "Tutorial OCC 7 room", "<p>OCC 7 meets in DK3.</p>"), activity(12, "folder", "Slides", "<ul><li>a.pdf</li></ul>")),
            section(2, 1, "Week 1", "", activity(21, "label", "Bring a laptop", "<p>Bring a laptop.</p>"), activity(22, "assign", "Lab 1", "<p>Submit by Friday.</p>"),
                activity(23, "label", "Hidden", "<p>Not for you.</p>").put("uservisible", false)),
            section(3, 2, "Week 2", "<p>Secret.</p>", visible = false),
        ), occ = 4, plain = plain)
        assertEquals(
            listOf(CourseUpdates.Text("s1", "General", "Read this first."), CourseUpdates.Text("m21", "Week 1", "Bring a laptop."), CourseUpdates.Text("m22", "Lab 1", "Submit by Friday.")),
            texts,
        )
    }

    @Test fun aChangedTextIsToldWithWhatItSaysNow() {
        val before = JSONObject().put("s1", "Read this first.").put("m21", "Bring a laptop.")
        val now = listOf(
            CourseUpdates.Text("s1", "General", "Read this first. Quiz 2 is moved to Friday."),
            CourseUpdates.Text("m21", "Week 1", "Bring a laptop."),
            // A text block added since, in a known activity's place: told only as the new activity it is.
            CourseUpdates.Text("m30", "Week 2", "Lab moved."),
            // A description newly shown under an activity that was there before.
            CourseUpdates.Text("m22", "Lab 1", "Submit by Friday."),
        )
        val told = CourseUpdates.told(before, now, known = setOf(21, 22))
        assertEquals(listOf("s1", "22"), told.map { it.getString("mark") })
        assertEquals("Quiz 2 is moved to Friday.", told[0].getString("said"))
        assertEquals("General", told[0].getString("name"))
        assertEquals("General: “Quiz 2 is moved to Friday.”\nLab 1: “Submit by Friday.”", CourseUpdates.text(told))
        assertEquals("New: Lab 3\nGeneral: “Quiz 2 is moved to Friday.”", CourseUpdates.text(listOf(JSONObject().put("name", "Lab 3").put("added", true), told[0])))
    }
}
