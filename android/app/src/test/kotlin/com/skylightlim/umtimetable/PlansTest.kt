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
        assertEquals(listOf(30, 60, 90, 120, 180), Plan.LENGTHS.map { it.first })
        assertEquals(listOf(null, 0, 10, 30, 60), Plan.REMINDERS.map { it.first })
    }
}
