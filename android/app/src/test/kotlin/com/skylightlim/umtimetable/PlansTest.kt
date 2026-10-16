package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun lengthsAndReminders() {
        // Every half hour that fits, for the plan card's tape.
        assertEquals(listOf(30 to "30 min", 60 to "1 h", 90 to "1.5 h", 120 to "2 h", 150 to "2.5 h"), Plan.lengths(170))
        assertEquals(22, Plan.lengths(11 * 60).size) // 08:00 to 19:00
        assertEquals(emptyList<Pair<Int, String>>(), Plan.lengths(20))
        assertEquals(listOf(null, 0, 5, 10, 15, 30, 60, 120, 1440), Plan.REMINDERS.map { it.first })
    }
}
