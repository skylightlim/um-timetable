package com.skylightlim.umtimetable

import org.json.JSONObject

/**
 * Which of a course's forums News reads. Each course has one Announcements forum (type "news"), but a
 * course set up one section per occurrence (GUL1001: a section for each of its groups) can give each
 * occurrence a forum of its own in its section ("Announcement OCC 4"), where its lecturer posts. News
 * read only the course's Announcements forum, so those posts never arrived.
 */
object NewsForums {
    /** A forum named for announcements, in English or Malay. */
    private val ANNOUNCING = Regex("""(?i)announce|pengumuman|makluman|notice|\bnews\b|berita""")
    /**
     * More announcement forums than this in one course, none naming an occurrence, are one per group
     * under the same name: whose is whose can't be told, so none of them is read.
     */
    const val PER_COURSE = 3

    /**
     * The forums to read, from `mod_forum_get_forums_by_courses`: every course's Announcements forum,
     * any forum named for the user's occurrence in that course ([occOf] its course id; 0 unknown), and up
     * to [PER_COURSE] more named for announcements and no occurrence. Forums named for other occurrences
     * only are left out.
     */
    fun pick(forums: List<JSONObject>, occOf: (Int) -> Int): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for ((course, inCourse) in forums.groupBy { it.optInt("course") }) {
            val occ = occOf(course)
            val announcing = ArrayList<JSONObject>()
            for (f in inCourse) {
                val name = f.optString("name")
                val occs = AttendanceMatch.occurrences(name)
                when {
                    f.optString("type") == "news" -> out += f
                    occs.isNotEmpty() -> if (occ > 0 && occ in occs) out += f
                    ANNOUNCING.containsMatchIn(name) -> announcing += f
                }
            }
            if (announcing.size <= PER_COURSE) out += announcing
        }
        return out
    }
}
