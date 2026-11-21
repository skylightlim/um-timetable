package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Auto attendance's log read back as how each class went (a made-up student, courses and log). */
class AttendanceHistoryTest {
    private val abc = Course("ABC1001", "Made-up Maths", "Maths", 3, 0)
    private val xyz = Course("XYZ2002", "Made-up Writing", "Writing", 5, 0)
    private fun s(course: Course, day: Int, start: Int, type: String = "Lecture") = Session(day, start, start + 120, type, "DK1", null, course)

    /** Mon: ABC lecture 08:00, XYZ tutorial 10:00, ABC tutorial 14:00. Tue: XYZ lecture 08:00. Wed: ABC lecture 08:00, XYZ lecture 12:00, ABC lab 16:00. */
    private val week = mapOf(
        1 to listOf(s(abc, 1, 480), s(xyz, 1, 600, "Tutorial"), s(abc, 1, 840, "Tutorial")),
        2 to listOf(s(xyz, 2, 480)),
        3 to listOf(s(abc, 3, 480), s(xyz, 3, 720), s(abc, 3, 960, "Lab")),
    )
    private fun classesOn(d: LocalDate) = week[d.dayOfWeek.value].orEmpty()

    private val log = AttendanceLog.parse(
        """
        2026-11-23 07:55:00 turned on
        2026-11-23 08:00:01 ABC1001 lecture 08:00: opening "Attendance OCC 3" (https://spectrum.example/view.php?id=1)
        2026-11-23 08:00:06 ABC1001 lecture 08:00: {"ok":true,"status":"Present","cmid":1}
        2026-11-23 10:00:02 XYZ2002 tutorial 10:00: opening "Attendance" (https://spectrum.example/view.php?id=2)
        2026-11-23 10:00:05 XYZ2002 tutorial 10:00: {"error":"password","rows":["Mon 23 Nov 2026 10AM - 12PM ? ? / 2"]}
        2026-11-24 08:00:01 XYZ2002 lecture 08:00: opening "Attendance" (https://spectrum.example/view.php?id=3)
        2026-11-24 08:00:30 XYZ2002 lecture 08:00: {"error":"unreachable","pages":""}; trying again at 08:02
        2026-11-24 15:00:00 OLD1001 lecture 15:00: {"ok":true,"already":true,"status":""}
        2026-11-25 08:00:00 ABC1001 lecture 08:00: no attendance activity for this class. Occ 3; its activities: "OCC 9" x
        2026-11-25 08:20:00 ABC1001 lecture 08:00: no attendance activity for this class, last try. Occ 3; its activities: "OCC 9" x
        2026-11-25 12:00:01 XYZ2002 lecture 12:00: {"error":"notopen","rows":[]}; trying again at 12:02
        2026-11-25 12:02:03 XYZ2002 lecture 12:00: {"error":"notopen","rows":[]}; trying again at 12:05
        """.trimIndent(),
    )
    private val now = LocalDateTime.of(2026, 11, 25, 12, 3)
    private val rows = AttendanceHistory.rows(log, now, 14, ::classesOn)

    private fun row(date: Int, start: Int, code: String) = rows.single { it.date.dayOfMonth == date && it.start == start && it.code == code }

    @Test fun newestDayFirstEachInTimeOrder() {
        assertEquals(
            listOf("25 480 ABC1001", "25 720 XYZ2002", "24 480 XYZ2002", "24 900 OLD1001", "23 480 ABC1001", "23 600 XYZ2002", "23 840 ABC1001"),
            rows.map { "${it.date.dayOfMonth} ${it.start} ${it.code}" },
        )
        // Not yet started today: not listed (ABC lab at 16:00). Before the log begins: nothing.
        assertTrue(rows.none { it.start == 960 })
    }

    @Test fun saysHowEachWent() {
        row(23, 480, "ABC1001").outcome.let {
            assertEquals(AttendanceHistory.Kind.MARKED, it.kind)
            assertEquals("Marked present at 08:00.", it.text)
            assertEquals("Attendance marked · Present at 08:00", it.short)
        }
        row(23, 600, "XYZ2002").outcome.let {
            assertTrue(it.missed)
            assertEquals("Not marked. The session needs your lecturer's password.", it.text)
        }
        // Tried, then no try came: the app stopped (the day is over).
        row(24, 480, "XYZ2002").outcome.let {
            assertTrue(it.missed)
            assertEquals("Not marked. Last tried at 08:00 (Spectrum unreachable), and no try came after it.", it.text)
        }
        // On the timetable, never in the log: not tried.
        row(23, 840, "ABC1001").outcome.let {
            assertEquals(AttendanceHistory.Kind.NOT_TRIED, it.kind)
            assertEquals("Not marked. The app didn't try to mark it.", it.text)
        }
        // In the log, no longer on the timetable: still listed.
        assertEquals("Already marked, by you or your lecturer.", row(24, 900, "OLD1001").outcome.text)
        row(25, 480, "ABC1001").outcome.let {
            assertEquals(AttendanceHistory.Kind.NOTHING, it.kind)
            assertFalse(it.missed)
        }
        // In progress, still being tried.
        row(25, 720, "XYZ2002").outcome.let {
            assertEquals(AttendanceHistory.Kind.TRYING, it.kind)
            assertFalse(it.missed)
            assertEquals("Attendance not marked yet · trying again at 12:05", it.short)
        }
    }

    @Test fun notWhileItWasOff() {
        val off = AttendanceLog.parse(
            """
            2026-11-23 07:00:00 turned on
            2026-11-23 09:00:00 turned off
            2026-11-24 07:00:00 turned on
            """.trimIndent(),
        )
        val r = AttendanceHistory.rows(off, LocalDateTime.of(2026, 11, 24, 20, 0), 14, ::classesOn)
        // Monday 08:00 counts (on), 10:00 and 14:00 don't (off); Tuesday's does.
        assertEquals(listOf("24 480", "23 480"), r.map { "${it.date.dayOfMonth} ${it.start}" })
        assertTrue(r.all { it.outcome.kind == AttendanceHistory.Kind.NOT_TRIED })
    }

    @Test fun theQrCodeOnItsPage() {
        val qr = AttendanceLog.parse("""2026-11-23 08:01:10 ABC1001 lecture 08:00: {"ok":true,"status":"Late","first":{"error":"notopen"}}""")
        val r = AttendanceHistory.rows(qr, LocalDateTime.of(2026, 11, 23, 20, 0), 14, ::classesOn).single { it.start == 480 }
        assertEquals("Marked late at 08:01, with the QR code on its page.", r.outcome.text)
    }

    @Test fun anAlarmThatNeverCame() {
        val slept = AttendanceLog.parse(
            """
            2026-11-23 07:55:00 turned on
            2026-11-23 08:31:00 missed: the 08:00 alarm never came; mobile data, battery Optimised, standby rare
            2026-11-23 10:00:01 XYZ2002 tutorial 10:00: {"error":"notopen","rows":[]}; trying again at 10:02
            2026-11-23 10:31:00 missed: the 10:02 alarm never came; mobile data, battery Optimised, standby rare
            2026-11-23 15:00:00 missed: the 14:00 alarm never came ${AutoAttendance.RESTARTED}; Wi-Fi, battery Optimised, standby active
            """.trimIndent(),
        )
        val r = AttendanceHistory.rows(slept, LocalDateTime.of(2026, 11, 23, 20, 0), 14, ::classesOn)
        fun at(start: Int) = r.single { it.start == start }.outcome
        // Never tried: the phone didn't wake the app, and what to do about it.
        at(480).let {
            assertEquals(AttendanceHistory.Kind.NOT_TRIED, it.kind)
            assertTrue(it.asleep && it.missed)
            assertEquals("Not marked: the phone didn't wake the app at 08:00 to mark it. Set the app's battery use to Unrestricted.", it.text)
            assertEquals("Attendance not marked · the phone didn't wake the app", it.short)
        }
        // Tried once, then the next try's alarm never came.
        at(600).let {
            assertTrue(it.asleep)
            assertEquals("Not marked. Last tried at 10:00 (no session open), then the phone didn't wake the app at 10:02 to try again. Set the app's battery use to Unrestricted.", it.text)
        }
        // The phone was off: no battery setting to blame.
        at(840).let {
            assertFalse(it.asleep)
            assertEquals("Not marked: the phone was off or restarting at 14:00 to mark it.", it.text)
        }
        // While the class is on: it'll try when the app next runs.
        val now = AttendanceHistory.rows(slept, LocalDateTime.of(2026, 11, 23, 8, 40), 14, ::classesOn).single { it.start == 480 }.outcome
        assertEquals(AttendanceHistory.Kind.TRYING, now.kind)
        assertEquals("Attendance not marked yet · the phone didn't wake the app", now.short)
        assertTrue(AttendanceLog.isProblem("missed: the 08:00 alarm never came; Wi-Fi"))
    }

    @Test fun namesClassesAsTheLogDoes() {
        assertEquals("XYZ2002 tutorial 10:00", AttendanceHistory.what(s(xyz, 1, 600, "Tutorial")))
    }
}
