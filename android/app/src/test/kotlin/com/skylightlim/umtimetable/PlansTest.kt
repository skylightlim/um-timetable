package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A plan as it's saved: its date, times, colour and reminder come back as they went. */
class PlansTest {
    @Test fun roundTrip() {
        val plan = Plan("a", LocalDate.parse("2026-11-10"), 10 * 60, 11 * 60 + 30, "Group study", 0xFF449DF0.toInt(), 10)
        assertEquals(plan, Plan.from(plan.json()))
        val bare = plan.copy(text = "", remind = null)
        assertEquals(bare, Plan.from(bare.json()))
        assertNull(Plan.from(bare.json()).remind)
        assertEquals("Plan", bare.title) // nothing written
    }

    /** A weekly plan comes back on its weekday each week to the end of its semester, but in skipped weeks. */
    @Test fun weekly() {
        fun d(s: String): LocalDate = LocalDate.parse(s)
        // A Tuesday in semester I 2026/27, whose exams end on Sunday 7 February 2027.
        val gym = Plan("g", d("2026-10-20"), 17 * 60, 18 * 60, "Gym", 0xFF449DF0.toInt(), weekly = true)
        assertEquals(d("2027-02-07"), gym.last)
        assertEquals(d("2026-10-27"), gym.on(d("2026-10-27"))?.date)
        assertEquals(d("2027-02-02"), gym.on(d("2027-02-02"))?.date) // the last Tuesday
        assertNull(gym.on(d("2027-02-09"))) // after the semester
        assertNull(gym.on(d("2026-10-13"))) // before it was set
        assertNull(gym.on(d("2026-10-28"))) // a Wednesday
        val copy = gym.on(d("2026-11-03"))!!
        assertEquals("g@2026-11-03", copy.reminderKey)
        assertEquals(d("2026-10-20"), copy.from) // still knows its first week
        assertTrue(copy.repeatsOn)
        // A week deleted alone, and a plan stopped after a week.
        assertNull(gym.copy(skip = setOf(d("2026-11-03"))).on(d("2026-11-03")))
        val stopped = gym.copy(until = d("2026-11-10"))
        assertEquals(d("2026-11-10"), stopped.on(d("2026-11-10"))?.date)
        assertNull(stopped.on(d("2026-11-17")))
        assertFalse(stopped.on(d("2026-11-10"))!!.repeatsOn)
        // Saved once, dated its first week, whichever week's copy it came from.
        val saved = copy.copy(skip = setOf(d("2026-12-01")), until = d("2027-01-12"))
        assertEquals("2026-10-20", saved.json().getString("date"))
        assertEquals(saved.copy(date = d("2026-10-20")), Plan.from(saved.json()))
        // A one-off plan is only on its day, and isn't "weekly" when read back.
        val once = gym.copy(weekly = false)
        assertNull(once.last)
        assertNull(once.on(d("2026-10-27")))
        assertFalse(Plan.from(once.json()).weekly)
    }

    /** A plan dragged on the week grid stays where the finger took it, rather than putting its start under the finger. */
    @Test fun dragHoldsWhereTaken() {
        // 10:00–13:00, taken by its bottom (the 12:00 half hour): held 120 minutes below its start.
        val grab = heldAt(12 * 60, 10 * 60, 13 * 60)
        assertEquals(120, grab)
        assertEquals(10 * 60, draggedStart(12 * 60, grab)) // not moved yet: still at 10:00
        assertEquals(10 * 60 + 30, draggedStart(12 * 60 + 30, grab)) // the finger half an hour down
        assertEquals(8 * 60, draggedStart(9 * 60, grab)) // not above 08:00
        assertEquals(0, heldAt(10 * 60, 10 * 60, 13 * 60)) // by its top
        assertEquals(0, heldAt(9 * 60 + 30, 10 * 60, 10 * 60 + 30)) // a half hour plan: nowhere else to hold it
        assertEquals(18 * 60 + 30, draggedStart(18 * 60 + 30, 0)) // a new plan: its start under the finger
        assertEquals(18 * 60 + 30, draggedStart(21 * 60, 0)) // the grid ends at 19:00: its last half hour
        // Week until midnight: the evening's half hours take a plan, to 23:30.
        assertEquals(21 * 60, draggedStart(21 * 60, 0, 23 * 60 + 30))
        assertEquals(23 * 60 + 30, draggedStart(23 * 60 + 30, 0, 23 * 60 + 30))
        assertEquals(22 * 60, draggedStart(23 * 60 + 30, 90, 23 * 60 + 30)) // held by its bottom
    }

    @Test fun lengthsAndReminders() {
        // Every half hour that fits, for the plan card's tape.
        assertEquals(listOf(30 to "30 min", 60 to "1 h", 90 to "1.5 h", 120 to "2 h", 150 to "2.5 h"), Plan.lengths(170))
        assertEquals(22, Plan.lengths(11 * 60).size) // 08:00 to 19:00
        assertEquals(emptyList<Pair<Int, String>>(), Plan.lengths(20))
        assertEquals(listOf(null, 0, 5, 10, 15, 30, 60, 120, 1440), Plan.REMINDERS.map { it.first })
    }
}
