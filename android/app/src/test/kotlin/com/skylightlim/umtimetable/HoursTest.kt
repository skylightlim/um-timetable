package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Test

/** The hours a week Courses shows: each course's by kind on its page, the week's total as "L12 T5". */
class HoursTest {
    private val course = Course("XYZ1001", "Made-up Course", "Made-up", 4, 0)

    private fun s(type: String, start: Int, end: Int, day: Int = 1) = Session(day, start, end, type, "DK1", null, course)

    @Test fun byKindLecturesFirst() {
        val week = listOf(s("Tutorial", 600, 660), s("Lab", 840, 960), s("Lecture", 480, 600), s("Lecture", 480, 570, day = 3))
        assertEquals("Lecture 3.5 h · Tutorial 1 h · Lab 2 h", hoursByType(week))
        assertEquals("L3.5 T1 Lab2", hoursShort(week))
    }

    @Test fun theUsualWeek() {
        val week = List(6) { s("Lecture", 480, 600, day = 1 + it % 5) } + List(5) { s("Tutorial", 600, 660, day = 1 + it % 5) }
        assertEquals("L12 T5", hoursShort(week))
        assertEquals("", hoursShort(emptyList()))
    }
}
