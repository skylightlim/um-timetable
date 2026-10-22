package com.skylightlim.umtimetable

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The timetable built from TimeEdit's answer, in TimeEdit's format as seen on 13 Oct 2026 (a made-up student's classes and lecturers). */
class TimeEditTimetableTest {

    private fun slot(code: String, day: Int, start: String, end: String, activity: String, room: String, weeks: Int, vararg lecturers: Pair<String, String>) =
        JSONObject().put("code", code).put("module", "$code MODULE").put("day", day).put("start", start).put("end", end)
            .put("activity", activity).put("room", room).put("weeks", weeks).put("term", "2026/S1")
            .put("lecturers", JSONArray(lecturers.map { (t, n) -> JSONObject().put("title", t).put("name", n) }))

    private val answer = JSONObject().put(
        "slots",
        JSONArray(
            listOf(
                slot("WIX1002", 4, "14:00", "17:00", "TUTORIAL", "MAKMAL GABUNG (MAKMAL MIKRO 8)", 6, "DR." to "AINA BINTI HASSAN"),
                slot("WIX1002", 3, "10:00", "12:00", "LECTURE", "DEWAN KULIAH 3 FSKTM", 6, "PROF. TS. DR." to "KAMAL ARIFFIN BIN MA AROF"),
                slot("GIG1003", 2, "17:00", "19:00", "LECTURE", "ONLINE LECTURE", 5, "ASSOCIATE PROF. DR." to "TAN WEI MING"),
                // A one-off (a replacement class) and a Saturday class aren't weekly classes of the timetable.
                slot("WIX1002", 1, "15:00", "17:00", "LECTURE", "DEWAN KULIAH 3 FSKTM", 1),
                slot("GIG1003", 6, "09:00", "11:00", "LECTURE", "ONLINE LECTURE", 6),
                // No lecturer on TimeEdit: the app's own is kept for the very same class.
                slot("WIX1001", 5, "08:00", "09:00", "TUTORIAL", "MAKMAL MIKRO 7 FSKTM", 6),
            ),
        ),
    )
    private val occurrences = JSONObject().put("GIG1003", 11).put("WIX1001", 5).put("WIX1002", 10).put("GUL1001", 11)
    private val spectrum = mapOf("WIX1002" to "WIX1002 FUNDAMENTALS OF PROGRAMMING", "GIG1003" to "GIG1003 BASIC ENTREPRENEURSHIP ENCULTURATION")
    private val current = JSONObject()
        .put("courses", JSONObject().put("WIX1001", JSONObject().put("name", "Computing Mathematics I").put("short", "Comp Math I").put("occ", 5).put("color", "#a586e8")))
        .put("sessions", JSONArray().put(JSONObject().put("day", 5).put("start", "08:00").put("end", "09:00").put("code", "WIX1001").put("type", "Tutorial").put("room", "MM7").put("lecturer", "Dr. Farid Osman")))

    @Test fun build() {
        val result = TimeEditTimetable.build(answer, occurrences, spectrum, current)!!
        assertEquals(4, result.classes)
        assertEquals(3, result.courses)
        assertEquals(listOf("GUL1001"), result.notFound)
        assertEquals("Sem 1 2026/27", result.term)

        val sessions = result.json.getJSONArray("sessions").objects()
        assertEquals(listOf("GIG1003 2 17:00", "WIX1002 3 10:00", "WIX1002 4 14:00", "WIX1001 5 08:00"), sessions.map { "${it.getString("code")} ${it.getInt("day")} ${it.getString("start")}" })
        val tutorial = sessions[2]
        assertEquals("Tutorial", tutorial.getString("type"))
        assertEquals("MM8", tutorial.getString("room"))
        assertEquals("Makmal Gabung (Makmal Mikro 8)", tutorial.getString("roomName"))
        assertEquals("Dr. Aina binti Hassan", tutorial.getString("lecturer"))
        assertEquals("Online", sessions[0].getString("room"))
        assertEquals("Assoc. Prof. Dr. Tan Wei Ming", sessions[0].getString("lecturer"))
        assertEquals("Dr. Farid Osman", sessions[3].getString("lecturer"))

        val courses = result.json.getJSONObject("courses")
        assertEquals("Fundamentals of Programming", courses.getJSONObject("WIX1002").getString("name"))
        assertEquals(10, courses.getJSONObject("WIX1002").getInt("occ"))
        // The app's own name, short name and colour stay.
        assertEquals("Comp Math I", courses.getJSONObject("WIX1001").getString("short"))
        assertEquals("#a586e8", courses.getJSONObject("WIX1001").getString("color"))
    }

    /** GIG1003 chosen as occurrence 4 while Spectrum has the student in 11: occurrence 11's class comes apart, under `registered`. */
    @Test fun registered() {
        val answer = JSONObject(answer.toString())
        answer.getJSONArray("slots").put(slot("GIG1003", 3, "08:00", "10:00", "LECTURE", "ONLINE LECTURE", 6, "DR." to "LEE MEI LING").put("registered", true))
        val chosen = JSONObject(occurrences.toString()).put("GIG1003", 4)
        val result = TimeEditTimetable.build(answer, chosen, spectrum, current, JSONObject().put("GIG1003", 11))!!
        assertEquals(4, result.classes)
        val gig = result.json.getJSONObject("courses").getJSONObject("GIG1003")
        assertEquals(4, gig.getInt("occ"))
        assertEquals(11, gig.getInt("registered"))
        assertEquals(listOf("GIG1003 3 08:00 Dr. Lee Mei Ling"), result.json.getJSONArray("registered").objects().map { "${it.getString("code")} ${it.getInt("day")} ${it.getString("start")} ${it.getString("lecturer")}" })
        // Spectrum's own occurrence chosen: nothing apart.
        val same = TimeEditTimetable.build(answer, occurrences, spectrum, current, JSONObject().put("GIG1003", 11))!!.json
        assertTrue(!same.has("registered") && !same.getJSONObject("courses").getJSONObject("GIG1003").has("registered"))
    }

    @Test fun nothingWeekly() {
        assertNull(TimeEditTimetable.build(JSONObject().put("slots", JSONArray()), occurrences, spectrum, current))
    }

    @Test fun term() {
        assertEquals("Sem 1 2026/27", TimeEditTimetable.term(listOf("2026/S1", "2026/S1", "")))
        assertEquals("Sem 2 2026/27", TimeEditTimetable.term(listOf("2026/S2")))
        assertNull(TimeEditTimetable.term(listOf("", "something")))
    }

    @Test fun compare() {
        val built = TimeEditTimetable.build(answer, occurrences, spectrum, current)!!.json
        val app = JSONObject(built.toString())
        val sessions = app.getJSONArray("sessions")
        sessions.getJSONObject(1).put("lecturer", "Dr. Kamal Ariffin") // the same person, shorter
        sessions.getJSONObject(0).put("room", "OL")
        sessions.getJSONObject(2).put("lecturer", "Dr. Someone Else")
        sessions.remove(3)
        sessions.put(JSONObject().put("day", 1).put("start", "09:00").put("end", "11:00").put("code", "GUL1001").put("type", "Lecture"))
        val diff = TimeEditTimetable.compare(app, built)
        assertEquals(1, diff.same)
        assertEquals(
            listOf("GIG1003 lecture, Tue 17:00–19:00: room OL → Online", "WIX1002 tutorial, Thu 14:00–17:00: lecturer Dr. Someone Else → Dr. Aina binti Hassan"),
            diff.changed,
        )
        assertEquals(listOf("WIX1001 tutorial, Fri 08:00–09:00"), diff.added)
        assertEquals(listOf("GUL1001 lecture, Mon 09:00–11:00"), diff.removed)
        assertTrue(TimeEditTimetable.compare(built, built).identical)
    }
}
