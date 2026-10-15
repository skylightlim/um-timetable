package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** Kuala Lumpur's public holidays, from the 2026 and 2027 schedules. */
class HolidaysTest {
    private fun on(date: String) = Holidays.on(LocalDate.parse(date))

    @Test fun thisSemester() {
        assertEquals("Deepavali", on("2026-11-08")?.label)
        assertEquals("Deepavali (replacement)", on("2026-11-09")?.label) // the 8th is a Sunday
        assertEquals("Christmas", on("2026-12-25")?.name)
        assertEquals("Federal Territory Day", on("2027-02-01")?.name) // Kuala Lumpur's own
        assertNull(on("2026-12-11")) // the Sultan of Selangor's birthday: Selangor's, not KL's
        assertNull(on("2027-01-06")) // Israk and Mikraj: not a holiday in KL
    }

    @Test fun replacementsFollowSundaysOnly() {
        for (h in Holidays.ALL.filter { it.replacement }) {
            assertEquals(DayOfWeek.MONDAY, h.date.dayOfWeek)
            assertTrue(Holidays.ALL.any { !it.replacement && it.date == h.date.minusDays(1) && it.date.dayOfWeek == DayOfWeek.SUNDAY })
        }
        assertNull(on("2027-05-03")) // Labour Day was a Saturday: no replacement
    }

    @Test fun listedDatesAreTheirWeekdays() {
        // The schedule's own weekdays, as a check on typing the dates.
        assertEquals(DayOfWeek.FRIDAY, on("2027-01-22")!!.date.dayOfWeek) // Thaipusam, Jumaat
        assertEquals(DayOfWeek.WEDNESDAY, on("2027-02-24")!!.date.dayOfWeek) // Nuzul Al-Quran, Rabu
        assertEquals(DayOfWeek.THURSDAY, on("2027-10-28")!!.date.dayOfWeek) // Deepavali, Khamis
    }
}
