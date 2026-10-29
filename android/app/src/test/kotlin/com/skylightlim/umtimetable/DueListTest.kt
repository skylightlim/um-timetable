package com.skylightlim.umtimetable

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** One Due row per activity, and none for other occurrences' copies of an assignment. */
class DueListTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun event(id: Int, name: String, due: Long, module: String = "assign", instance: Int = id, type: String = "due", course: Int = 7, code: String = "XYZ1001") =
        JSONObject().put("id", id).put("courseId", course).put("code", code).put("module", module).put("instance", instance)
            .put("eventtype", type).put("name", name).put("due", due)

    private fun ids(items: List<JSONObject>) = items.map { it.getInt("id") }

    @Test fun quizOpenAndCloseIsOneRowTheClose() {
        val open = event(1, "Quiz 2", now + day, module = "quiz", instance = 40, type = "open")
        val close = event(2, "Quiz 2", now + 3 * day, module = "quiz", instance = 40, type = "close")
        assertEquals(listOf(2), ids(DueList.distinct(listOf(open, close), now)))
    }

    @Test fun dueDateWinsOverExpectedCompletion() {
        val expected = event(3, "Lab report", now + day, instance = 9, type = "expectcompletionon")
        val due = event(4, "Lab report", now + 2 * day, instance = 9, type = "due")
        assertEquals(listOf(4), ids(DueList.distinct(listOf(expected, due), now)))
    }

    @Test fun groupOverrideKeepsTheNextStillToCome() {
        val passed = event(5, "Essay", now - day, instance = 3)
        val coming = event(6, "Essay", now + day, instance = 3)
        val later = event(7, "Essay", now + 5 * day, instance = 3)
        assertEquals(listOf(6), ids(DueList.distinct(listOf(passed, coming, later), now)))
        assertEquals(listOf(5), ids(DueList.distinct(listOf(passed, event(8, "Essay", now - 2 * day, instance = 3)), now)))
    }

    @Test fun sameNameAndTimeInACourseIsOne() {
        val a = event(10, "Assignment 1 ", now + day, instance = 11)
        val b = event(11, "assignment 1", now + day, instance = 12)
        val otherCourse = event(12, "Assignment 1", now + day, instance = 13, course = 8)
        val otherTime = event(13, "Assignment 1", now + 2 * day, instance = 14)
        assertEquals(listOf(10, 12, 13), ids(DueList.distinct(listOf(a, b, otherCourse, otherTime), now)))
    }

    @Test fun differentActivitiesStay() {
        val items = listOf(event(20, "Tutorial 1", now + day), event(21, "Tutorial 2", now + day), event(22, "Forum post", now + day, module = "forum"))
        assertEquals(listOf(20, 21, 22), ids(DueList.distinct(items, now)))
    }

    @Test fun otherOccurrencesCopiesGo() {
        val items = listOf(
            event(30, "Assignment 1 (OCC 3)", now + day),
            event(31, "Assignment 1 (OCC 4)", now + day),
            event(32, "Assignment 1 for OCC4, OCC7", now + day),
            event(33, "Assignment 2", now + day),
            event(34, "Lab 1 - OCC3 Submission", now + day, code = "XYZ1002"),
        )
        assertEquals(listOf(31, 32, 33, 34), ids(DueList.forMe(items) { if (it == "XYZ1001") 4 else 0 }))
    }
}
