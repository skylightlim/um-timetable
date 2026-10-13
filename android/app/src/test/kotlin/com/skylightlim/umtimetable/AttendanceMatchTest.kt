package com.skylightlim.umtimetable

import com.skylightlim.umtimetable.AttendanceMatch.Owner.MINE
import com.skylightlim.umtimetable.AttendanceMatch.Owner.OTHER
import com.skylightlim.umtimetable.AttendanceMatch.Owner.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Attendance activities shaped like the ones seen on Spectrum on 13 Oct 2026 (the same kinds of names:
 * occurrence numbers, group labels, rooms, initials, weekdays), against a student's own classes. The
 * student, their occurrences, days, rooms and the lecturers' names are made up.
 */
class AttendanceMatchTest {

    private fun course(code: String, occ: Int) = Course(code, code, code, occ, 0)
    private fun cls(c: Course, day: Int, type: String, room: String, lecturer: String? = null) =
        Session(day, 0, 0, type, room, lecturer, c)
    private fun activities(vararg names: String) = names.mapIndexed { i, n -> AttendanceMatch.Activity(i + 1, i + 1, n) }

    @Test fun occurrenceNumbers() {
        val cases = mapOf(
            "Attendance OCC 10 (Thursday - 2 PM- 5 PM) - Dr. Aina" to setOf(10),
            "Attendance OCC11 (Tue 8am - 11am) -With Dr Mei" to setOf(11),
            "Attendance (Tutorials, OCC 4, 5, 7, 10)" to setOf(4, 5, 7, 10),
            "Attendance (OCC5 Only)" to setOf(5),
            "OCC 3" to setOf(3),
            "Attendance GIG1003 APK for Occ 11 (AP Dr Tan)" to setOf(11),
            "Attendance Occ 12 - GIG1003 Sem I 2026/2027" to setOf(12),
            "OCC/Group 11 Attendance | 2026/2027 Sem 1 (Time/ Day: 8 - 10 am/ Wednesday, Venue: Dewan Kuliah 3 (H09), FPE)" to setOf(11),
            "Attendance OCC 5 Dr Leila Haddad (Tuesday 8 -11)" to setOf(5),
            "Occurrence 3-5" to setOf(3, 4, 5),
            "OCC 1 & 2" to setOf(1, 2),
            // A time after the number isn't more occurrences.
            "OCC 7 - 11 AM" to setOf(7),
            "OCC 2 - 8:00" to setOf(2),
            "K (L) Attendance (Wed 10am-12pm) @ DK3 - KAM" to emptySet(),
            "Attendance WIX2001 2026/2027 - all groups" to emptySet(),
            "Attendance Sem1 26/27" to emptySet(),
            "Tutorial T4 By Imran Malik - Attendance" to emptySet(),
        )
        for ((name, occs) in cases) assertEquals(name, occs, AttendanceMatch.occurrences(name))
    }

    /** The bug: another group's tutorial session (T4, unrestricted) was offered during the student's own. */
    @Test fun wix1002() {
        val c = course("WIX1002", 10)
        val lecture = cls(c, 3, "Lecture", "DK3", "Prof. Ts. Dr. Kamal Ariffin bin Ma'arof")
        val tutorial = cls(c, 4, "Tutorial", "MM6", "Dr. Aina binti Hassan")
        val list = activities(
            "Tutorial T4 By Imran Malik - Attendance",
            "Attendance k ( L) @ Bilik Kuliah Wednesday 10:00 - 12:00 (Leila Haddad)",
            "Attendance OCC 10 (Thursday - 2 PM- 5 PM) - Dr. Aina",
            "K (L) Attendance (Wed 10am-12pm) @ DK3 - KAM",
        )
        val (t4, other, occ10, k) = list
        assertEquals(k, AttendanceMatch.pick(list, 10, lecture))
        assertEquals(occ10, AttendanceMatch.pick(list, 10, tutorial))
        val classes = listOf(lecture, tutorial)
        assertEquals(OTHER, AttendanceMatch.owner(t4, list, 10, classes))
        assertEquals(OTHER, AttendanceMatch.owner(other, list, 10, classes))
        assertEquals(MINE, AttendanceMatch.owner(occ10, list, 10, classes))
        assertEquals(MINE, AttendanceMatch.owner(k, list, 10, classes))
    }

    /** The student's occurrence number on the tutorials' activity mustn't win the lecture. */
    @Test fun wix1001() {
        val c = course("WIX1001", 5)
        val lecture = cls(c, 2, "Lecture", "DK1", "Dr. Hafiz Iskandar")
        val tutorial = cls(c, 5, "Tutorial", "MM2")
        val (lectures, tutorials) = activities("Attendance (Lectures)", "Attendance (Tutorials, OCC 4, 5, 7, 10)")
        val list = listOf(lectures, tutorials)
        assertEquals(lectures, AttendanceMatch.pick(list, 5, lecture))
        assertEquals(tutorials, AttendanceMatch.pick(list, 5, tutorial))
    }

    /** Other occurrences' activities go; generic names may be everyone's and stay. */
    @Test fun gig1003() {
        val c = course("GIG1003", 11)
        val lecture = cls(c, 4, "Lecture", "Online", "Assoc. Prof. Dr. Tan Wei Ming")
        val list = activities(
            "Attendance Occ 1 - GIG1003 Sem I 2026/2027",
            "Attendance Occ 12 - GIG1003 Sem I 2026/2027",
            "Attendance (OCC5 Only)",
            "Attendance GIG1003 APK for Occ 11 (AP Dr Tan)",
            "Attendance GIG1003 APK for Occ 10 (AP Dr Tan)",
            "Attendance of Lectures",
            "Attendance",
        )
        assertEquals(list[3], AttendanceMatch.pick(list, 11, lecture))
        val owners = list.map { AttendanceMatch.owner(it, list, 11, listOf(lecture)) }
        assertEquals(listOf(OTHER, OTHER, OTHER, MINE, OTHER, UNKNOWN, UNKNOWN), owners)
    }

    @Test fun gig1017() {
        val c = course("GIG1017", 3)
        val lecture = cls(c, 1, "Lecture", "Online")
        val list = activities("OCC 1", "OCC 2", "OCC 11", "OCC 3", "OCC 15")
        assertEquals(list[3], AttendanceMatch.pick(list, 3, lecture))
        assertEquals(listOf(OTHER, OTHER, OTHER, MINE, OTHER), list.map { AttendanceMatch.owner(it, list, 3, listOf(lecture)) })
    }

    /** A course's only activity covers all its classes, unless its name says it can't. */
    @Test fun singleActivity() {
        val wix1003 = course("WIX1003", 10)
        val only = activities("Attendance Sem1 26/27")
        assertEquals(only[0], AttendanceMatch.pick(only, 10, cls(wix1003, 1, "Lecture", "DK1")))
        assertEquals(only[0], AttendanceMatch.pick(only, 10, cls(wix1003, 2, "Tutorial", "MM4")))

        val gul1001 = course("GUL1001", 11)
        val gul = activities("OCC/Group 11 Attendance | 2026/2027 Sem 1 (Time/ Day: 8 - 10 am/ Wednesday, Venue: Dewan Kuliah 3 (H09), FPE)")
        assertEquals(gul[0], AttendanceMatch.pick(gul, 11, cls(gul1001, 3, "Lecture", "DK3 H09")))

        val lectureOnly = activities("K (L) Attendance (Wed 10am-12pm) @ DK3 - KAM")
        assertNull(AttendanceMatch.pick(lectureOnly, 10, cls(course("WIX1002", 10), 4, "Tutorial", "MM6")))
    }

    /** Two course-wide activities: nothing to pick, and neither is left out. */
    @Test fun wix2001() {
        val c = course("WIX2001", 10)
        val classes = listOf(cls(c, 2, "Lecture", "MM7"), cls(c, 3, "Tutorial", "MM7"))
        val list = activities("WIX2001 S1 2026/2027 Briefing Attendance", "Attendance WIX2001 2026/2027 - all groups")
        for (s in classes) assertNull(AttendanceMatch.pick(list, 10, s))
        assertEquals(listOf(UNKNOWN, UNKNOWN), list.map { AttendanceMatch.owner(it, list, 10, classes) })
    }
}
