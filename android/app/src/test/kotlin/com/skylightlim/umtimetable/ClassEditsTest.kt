package com.skylightlim.umtimetable

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The class sheet's pen: what a change reaches, and how a changed class is still known by what TimeEdit gave it. */
class ClassEditsTest {
    private val maths = Course("WIA1001", "Computing Mathematics I", "Maths", 5, 0xFFA586E8.toInt())
    private val other = Course("WIA1002", "Data Structures", "DS", 3, 0xFF4F9CF0.toInt())
    private val lecture = Session(2, 600, 720, "Lecture", "DK1", "Dr. A", maths, roomName = "Dewan Kuliah 1")
    private val tutorial = Session(5, 840, 900, "Tutorial", "BK2", "Dr. B", maths)
    private val ds = Session(3, 480, 600, "Lecture", "DK3", null, other)
    private val tt = Timetable(listOf(lecture, tutorial, ds), registered = listOf(tutorial.copy(course = maths.copy(occ = 7))))

    private fun edits(json: String) = JSONObject(json)

    @Test fun nothingChangedIsTheSameTimetable() {
        assertSame(tt, tt.withEdits(edits("{}")))
    }

    @Test fun aCourseChangeReachesEveryClassOfIt() {
        val e = tt.withEdits(edits("""{"courses": {"WIA1001": {"name": "Maths for Computing", "slot": 2}}}"""))
        assertEquals(listOf("Maths for Computing", "Maths for Computing", "Data Structures"), e.sessions.map { it.course.name })
        assertEquals("Maths for Computing", e.courses.first { it.code == "WIA1001" }.short)
        // Slot 2 of the import palette, which the theme then recolours as it does any course.
        assertEquals(0xFFF0913C.toInt(), e.sessions[0].course.color)
        assertEquals(other.color, e.sessions[2].course.color)
        // Spectrum's occurrence's classes (for attendance) keep their own occurrence.
        assertEquals("Maths for Computing", e.registered.single().course.name)
        assertEquals(7, e.registered.single().course.occ)
        // Unchanged classes are as given.
        assertNull(e.sessions[0].origin)
    }

    @Test fun aClassChangeReachesThatClassOnly() {
        val key = ClassEdits.key(lecture)
        assertEquals("WIA1001|2|600|Lecture", key)
        val e = tt.withEdits(edits("""{"classes": {"$key": {"day": 4, "start": 540, "end": 630, "type": "Tutorial", "room": "MM3", "lecturer": ""}}}"""))
        val moved = e.sessions[0]
        assertEquals(listOf(4, 540, 630, "Tutorial", "MM3"), listOf(moved.day, moved.start, moved.end, moved.type, moved.room))
        // A typed room has no long name, and a cleared lecturer is none.
        assertNull(moved.roomName)
        assertNull(moved.lecturer)
        // Still known by what TimeEdit gave it, so it can be changed again.
        assertEquals(key, ClassEdits.key(moved))
        assertEquals(lecture, moved.origin)
        assertEquals(tutorial, e.sessions[1])
        assertEquals(4, e.day(4).single().day)
    }

    @Test fun keepsAChangedClassSensible() {
        val key = ClassEdits.key(ds)
        val e = tt.withEdits(edits("""{"classes": {"$key": {"day": 9, "start": 1400, "end": 1300}}}"""))
        val s = e.sessions[2]
        assertEquals(5, s.day)
        assertEquals(1400, s.start)
        assertEquals(1405, s.end)
    }
}
