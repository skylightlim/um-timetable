package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

/** The alarms' day maths on java.time, checked against the Calendar code it replaced. */
class StartOfDayTest {
    private fun calendarStart(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun sameAsCalendar() {
        val was = TimeZone.getDefault()
        try {
            for (zone in listOf("Asia/Kuala_Lumpur", "Europe/London")) { // London: a day 23 h and one 25 h long
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                var date = LocalDate.of(2026, 10, 20)
                repeat(400) {
                    val midnight = date.atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()
                    val last = date.plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli() - 1
                    for (ms in listOf(midnight, midnight + 1, midnight + 13 * 3_600_000L, last)) {
                        assertEquals(calendarStart(ms), startOfDay(ms))
                    }
                    assertEquals(midnight, startOfDay(date))
                    // Sunday 0 … Saturday 6, as Calendar.DAY_OF_WEEK - 1 gave
                    val cal = Calendar.getInstance().apply { timeInMillis = midnight }
                    assertEquals(cal.get(Calendar.DAY_OF_WEEK) - 1, date.dayOfWeek.value % 7)
                    date = date.plusDays(1)
                }
            }
        } finally {
            TimeZone.setDefault(was)
        }
    }
}
