package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Test

/** The day Day opens on, and which week the day strip's dates belong to (a made-up timetable). */
class HomeDayTest {
    private val course = Course("ABC1001", "Made-up Course", "Made-up", 1, 0)
    private fun session(day: Int, start: Int, end: Int) = Session(day, start, end, "Lecture", "DK1", null, course)

    /** Classes on Monday, Tuesday and Friday. */
    private val tt = Timetable(listOf(session(1, 480, 600), session(2, 660, 780), session(5, 540, 660)))

    private fun strip(tt: Timetable, now: Now) = (1..5).map { daysFromToday(tt, now, it) }

    @Test fun beforeTheDaysLastClass() {
        val now = Now(2, 700f) // Tuesday 11:40
        assertEquals(2, homeDay(tt, now))
        assertEquals(listOf(-1, 0, 1, 2, 3), strip(tt, now))
    }

    @Test fun afterTheDaysLastClass() {
        val now = Now(2, 800f) // Tuesday 13:20: Friday's class is next, still this week
        assertEquals(5, homeDay(tt, now))
        assertEquals(listOf(-1, 0, 1, 2, 3), strip(tt, now))
    }

    @Test fun afterTheWeeksLastClassTheStripShowsNextWeek() {
        val now = Now(5, 1200f) // Friday 20:00
        assertEquals(1, homeDay(tt, now))
        assertEquals(listOf(3, 4, 5, 6, 7), strip(tt, now))
    }

    @Test fun weekend() {
        assertEquals(listOf(2, 3, 4, 5, 6), strip(tt, Now(6, 600f)))
        assertEquals(listOf(1, 2, 3, 4, 5), strip(tt, Now(0, 600f)))
    }

    @Test fun onlyOneClassDay() {
        val tuesdays = Timetable(listOf(session(2, 660, 780)))
        val now = Now(2, 800f) // after Tuesday's class: next Tuesday
        assertEquals(2, homeDay(tuesdays, now))
        assertEquals(listOf(6, 7, 8, 9, 10), strip(tuesdays, now))
    }

    @Test fun noTimetable() {
        val empty = Timetable(emptyList())
        assertEquals(3, homeDay(empty, Now(3, 600f)))
        assertEquals(listOf(-2, -1, 0, 1, 2), strip(empty, Now(3, 600f)))
        assertEquals(1, homeDay(empty, Now(6, 600f)))
        assertEquals(listOf(2, 3, 4, 5, 6), strip(empty, Now(6, 600f)))
    }
}
