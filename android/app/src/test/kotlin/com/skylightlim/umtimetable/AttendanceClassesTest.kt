package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Timetable.attendance]: a course whose occurrence the user changed is still marked at Spectrum's (made-up classes). */
class AttendanceClassesTest {
    private val gig = Course("GIG1003", "Made-up Course", "Made-up", 4, 0, registered = 11)
    private val wix = Course("WIX1002", "Other Course", "Other", 10, 0)
    private fun session(course: Course, day: Int, start: Int) = Session(day, start, start + 120, "Lecture", "Online", null, course)

    @Test fun registeredClassesStandIn() {
        val tt = Timetable(listOf(session(gig, 2, 1020), session(wix, 3, 600)), registered = listOf(session(gig.copy(occ = 11), 3, 480)))
        // The timetable shows the chosen occurrence's class (Tuesday); attendance is taken at Spectrum's (Wednesday).
        assertEquals(listOf(4), tt.day(2).map { it.course.occ })
        assertTrue(tt.attendanceDay(2).isEmpty())
        assertEquals(listOf("GIG1003 11 480", "WIX1002 10 600"), tt.attendanceDay(3).map { "${it.course.code} ${it.course.occ} ${it.start}" })
    }

    @Test fun chosenClassesWithSpectrumsNumber() {
        // Before TimeEdit has brought Spectrum's occurrence's classes.
        val tt = Timetable(listOf(session(gig, 2, 1020), session(wix, 3, 600)))
        assertEquals(listOf("GIG1003 11 1020"), tt.attendanceDay(2).map { "${it.course.code} ${it.course.occ} ${it.start}" })
        assertEquals(tt.day(3), tt.attendanceDay(3))
    }
}
