package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Reading auto attendance's log back for its page in Settings, and which steps count as problems. */
class AttendanceLogTest {
    @Test fun parsesLinesAndCarriesOnUntimedOnes() {
        val log = """
            2026-10-27 08:00:02 alarm, 2s after it was due
            2026-10-27 08:00:03 run: 1 class(es) to mark; Wi-Fi, background data allowed
            2026-10-27 08:00:09 run failed: java.lang.IllegalStateException: boom
            	at somewhere
            not a time
        """.trimIndent()
        val entries = AttendanceLog.parse(log)
        assertEquals(3, entries.size)
        assertEquals(LocalDateTime.of(2026, 10, 27, 8, 0, 2), entries[0].time)
        assertEquals("alarm, 2s after it was due", entries[0].text)
        assertEquals("run failed: java.lang.IllegalStateException: boom\n\tat somewhere\nnot a time", entries[2].text)
        assertEquals(emptyList<AttendanceLog.Entry>(), AttendanceLog.parse(""))
    }

    @Test fun problems() {
        for (text in listOf(
            "attendance read failed: no network (using the last one): none today",
            "XYZ1001 lecture 09:00: {\"error\":\"notopen\"}; trying again at 09:05",
            "XYZ1001 lecture 09:00: {\"error\":\"failed\",\"detail\":\"no confirmation from Spectrum\"}",
            "XYZ1001 lecture 09:00: no attendance activity for this class, last try. none",
            "run failed: java.lang.RuntimeException",
            "ran out of time",
            "notifications are off: the user isn't told",
        )) assertTrue(text, AttendanceLog.isProblem(text))
        for (text in listOf(
            "alarm, 3s after it was due",
            "turned on",
            "run: 0 class(es) to mark; Wi-Fi, background data allowed",
            "XYZ1001 lecture 09:00: {\"ok\":true,\"status\":\"Present\"}",
            "XYZ1001 lecture 09:00: {\"ok\":true,\"already\":true,\"status\":\"present\"}",
            "next try 2026-10-28 08:00:00",
        )) assertFalse(text, AttendanceLog.isProblem(text))
    }
}
