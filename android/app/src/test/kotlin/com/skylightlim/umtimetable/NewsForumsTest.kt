package com.skylightlim.umtimetable

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** News reads each course's Announcements, and the forum of the user's own occurrence where there is one. */
class NewsForumsTest {
    private fun forum(id: Int, name: String, type: String = "general", course: Int = 1) =
        JSONObject().put("id", id).put("course", course).put("name", name).put("type", type)

    private fun picked(forums: List<JSONObject>, occ: Int = 4) = NewsForums.pick(forums) { occ }.map { it.getInt("id") }.sorted()

    @Test fun announcementsAndMyOccurrencesForum() {
        val forums = listOf(
            forum(1, "Announcements", type = "news"),
            forum(2, "Announcement OCC 4"),
            forum(3, "Announcement OCC 7"),
            forum(4, "Forum Occ4"),
            forum(5, "Q&A"),
            forum(6, "Group 4 discussion"),
        )
        assertEquals(listOf(1, 2, 4, 6), picked(forums))
    }

    @Test fun occurrenceUnknownReadsOnlyUnnamedOnes() {
        val forums = listOf(forum(1, "Announcements", type = "news"), forum(2, "Announcement OCC 4"), forum(3, "Pengumuman Kursus"))
        assertEquals(listOf(1, 3), picked(forums, occ = 0))
    }

    @Test fun oneAnnouncementForumPerGroupIsTooManyToTell() {
        val many = (2..2 + NewsForums.PER_COURSE).map { forum(it, "Announcement") }
        assertEquals(listOf(1), picked(listOf(forum(1, "News forum", type = "news")) + many))
        val few = (2 until 2 + NewsForums.PER_COURSE).map { forum(it, "Notices") }
        assertEquals(listOf(1) + few.map { it.getInt("id") }, picked(listOf(forum(1, "News forum", type = "news")) + few))
    }

    @Test fun eachCourseItsOwnOccurrence() {
        val forums = listOf(forum(1, "Announcement OCC 4", course = 10), forum(2, "Announcement OCC 4", course = 20), forum(3, "Announcement OCC 3", course = 20))
        assertEquals(listOf(1, 3), NewsForums.pick(forums) { if (it == 10) 4 else 3 }.map { it.getInt("id") }.sorted())
    }
}
