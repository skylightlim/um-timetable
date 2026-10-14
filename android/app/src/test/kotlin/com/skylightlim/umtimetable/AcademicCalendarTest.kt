package com.skylightlim.umtimetable

import com.skylightlim.umtimetable.AcademicCalendar.Kind
import com.skylightlim.umtimetable.AcademicCalendar.Week
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Weeks of the semester from UM's 2026/2027 calendar (Senate amendment, 22 July 2026). */
class AcademicCalendarTest {
    private fun week(date: String) = AcademicCalendar.week(LocalDate.parse(date))

    @Test fun semesterOne() {
        assertNull(week("2026-10-04")) // Sunday, orientation week
        assertEquals(Week(Kind.TEACHING, 1, 14), week("2026-10-05"))
        assertEquals(Week(Kind.TEACHING, 2, 14), week("2026-10-13"))
        assertEquals(Week(Kind.TEACHING, 2, 14), week("2026-10-18")) // Sunday ends the week
        assertEquals(Week(Kind.TEACHING, 8, 14), week("2026-11-27"))
        assertEquals(Week(Kind.BREAK), week("2026-12-02"))
        assertEquals(Week(Kind.TEACHING, 9, 14), week("2026-12-07")) // the break isn't a teaching week
        assertEquals(Week(Kind.TEACHING, 14, 14), week("2027-01-15"))
        assertEquals(Week(Kind.REVISION), week("2027-01-20"))
        assertEquals(Week(Kind.EXAMS), week("2027-02-04"))
        assertNull(week("2027-02-08")) // semester break
    }

    @Test fun semesterTwoAndSpecial() {
        assertEquals(Week(Kind.TEACHING, 1, 14), week("2027-03-01"))
        assertEquals(Week(Kind.BREAK), week("2027-04-21"))
        assertEquals(Week(Kind.TEACHING, 8, 14), week("2027-04-26"))
        assertEquals(Week(Kind.TEACHING, 1, 7), week("2027-07-19"))
        assertEquals(Week(Kind.EXAMS), week("2027-09-10"))
        assertNull(week("2027-10-04"))
    }
}
