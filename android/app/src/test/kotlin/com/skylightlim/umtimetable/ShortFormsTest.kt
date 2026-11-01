package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A course's short name: what's kept of what's typed, what's suggested, and where it lands in the timetable. */
class ShortFormsTest {
    private val programming = Course("WIA1000", "Fundamentals of Programming", "Programming", 3, 0)
    private val maths = Course("WIA1001", "Computing Mathematics I", "Maths", 3, 0)

    @Test fun suggestsInitials() {
        assertEquals("FOP", ShortForms.suggest("Fundamentals of Programming"))
        assertEquals("CMI", ShortForms.suggest("Computing Mathematics I"))
        assertEquals("TC", ShortForms.suggest("Thinking & Communication"))
        assertEquals("", ShortForms.suggest(""))
    }

    @Test fun keepsOneTidyLine() {
        assertEquals("FOP", ShortForms.clean("  FOP "))
        assertEquals("OS 2", ShortForms.clean("OS \n  2"))
        assertEquals("", ShortForms.clean("   "))
        assertEquals(ShortForms.MAX, ShortForms.clean("x".repeat(40)).length)
    }

    @Test fun reachesEveryClassOfItsCourseOnly() {
        val lecture = Session(1, 600, 660, "Lecture", "A1", null, programming)
        val tutorial = Session(2, 660, 720, "Tutorial", "A2", null, programming)
        val other = Session(3, 660, 720, "Lecture", "A3", null, maths)
        val named = Timetable(listOf(lecture, tutorial, other), registered = listOf(lecture)).withForms(mapOf("WIA1000" to "FOP"))
        assertEquals(listOf("FOP", "FOP", null), named.sessions.map { it.course.form })
        assertEquals("FOP", named.courses.first { it.code == "WIA1000" }.form)
        assertEquals("FOP", named.registered.single().course.form)
        assertNull(named.courses.first { it.code == "WIA1001" }.form)
    }
}
