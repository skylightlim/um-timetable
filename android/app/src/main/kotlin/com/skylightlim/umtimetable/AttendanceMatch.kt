package com.skylightlim.umtimetable

/**
 * Which of a course's attendance activities are the user's. Courses often have one per occurrence or
 * per class, and some lecturers don't restrict theirs to their group, so Spectrum shows the user other
 * groups' too, and the calendar can list another group's session at the very hour of the user's own
 * tutorial (WIX1002, 13 Oct 2026), while the user's own activity never creates calendar events. Only
 * the names tell them apart: "Attendance OCC 10 (Thursday - 2 PM- 5 PM) - Dr. Aina", "K (L) Attendance
 * (Wed 10am-12pm) @ DK3 - KAM", "Tutorial T4 By Imran Malik - Attendance" (see AttendanceMatchTest; the
 * names there are made up).
 */
object AttendanceMatch {
    data class Activity(val cmid: Int, val instance: Int, val name: String)

    enum class Owner { MINE, OTHER, UNKNOWN }

    // "OCC 10", "OCC11", "Occ. 3 & 4", "Occurrence 1-3", "OCC/Group 11" (Spectrum's groups are the
    // occurrences: "GROUP 10 2026/2027_1"); a number followed by am/pm or ":" is a time.
    private const val N = """\d++(?!\s*(?:[ap]\.?m\b|[:.]\d))"""
    private val OCC = Regex("""(?i)\b(?:occ(?:urrence)?\.?(?:\s*/\s*group)?|group)\s*($N(?:\s*(?:,|&|/|and|-|–|to)\s*$N)*)""")
    // What only one group's activity names: a group label (T4, K1), a weekday, a time, a room after
    // "@" or a person. "Attendance of Lectures" names none, so it may be everyone's.
    private val SPECIFIC = Regex("""\b[A-Z]\d{1,2}\b|@\s*\S|\d\s*[ap]\.?m\b|\b\d{1,2}[:.]\d\d\b|\b(?:[Dd]r|[Pp]rof|[Bb]y|[Ww]ith)\.?\s+\p{Lu}""")
    private val RANGE = Regex("""^(\d+)\s*(?:-|–|to)\s*(\d+)$""")
    private val DAYS = listOf("mon(day)?|isnin", "tue(s|sday)?|selasa", "wed(nesday)?|rabu", "thu(r|rs|rsday)?|khamis", "fri(day)?|jumaat")
        .map { Regex("""(?i)\b($it)\b""") }
    private val KINDS = listOf(
        "lecture" to Regex("""(?i)\(\s*l\s*\)|\blecture|\bkuliah"""),
        "tutorial" to Regex("""(?i)\(\s*t\s*\)|\btutorial|\btut\b"""),
        "lab" to Regex("""(?i)\blab|\bamali|\bpractical"""),
    )
    private val TITLES = setOf(
        "dr", "prof", "assoc", "madya", "ts", "ir", "sr", "mr", "ms", "mrs", "madam", "en", "pn", "puan", "encik",
        "bin", "binti", "bt", "al", "ap", "datuk", "dato", "datin", "tan", "sri", "haji", "hajah", "hj",
    )
    private val INITIALS = Regex("""\b[A-Z]{2,5}\b""")
    private val NOT_INITIALS = setOf("AM", "PM", "OCC", "UM")

    /** The occurrence numbers in an activity's name, empty when it names none. */
    fun occurrences(name: String): Set<Int> = OCC.findAll(name).flatMap { m ->
        m.groupValues[1].split(Regex("""\s*(?:,|&|/|and)\s*""")).flatMap { part ->
            RANGE.find(part.trim())?.let {
                val (a, b) = it.groupValues[1].toInt() to it.groupValues[2].toInt()
                if (a < b) (a..b).toList() else listOf(a, b)
            }
                ?: Regex("""\d+""").findAll(part).map { it.value.toInt() }.toList()
        }
    }.toSet()

    /** [name] names occurrences, and [occ] (the user's) isn't one of them: "Lab 3 - OCC3 Submission" for OCC 10. */
    fun othersOnly(name: String, occ: Int): Boolean = occ > 0 && occurrences(name).let { it.isNotEmpty() && occ !in it }

    private fun kind(text: String) = KINDS.firstOrNull { it.second.containsMatchIn(text) }?.first

    /**
     * How well [name] fits the user's class [s] in occurrence [occ]: null when it can't be that class's,
     * because it names other occurrences only, another weekday or another kind of class ("Attendance
     * (Tutorials, OCC 4, 7, 10, 14)" isn't WIX1001's lecture). Occurrence +5; room and lecturer +2 each;
     * weekday and kind +1.
     */
    fun score(name: String, occ: Int, s: Session): Int? {
        val occs = occurrences(name)
        if (occs.isNotEmpty() && occ > 0 && occ !in occs) return null
        var score = if (occ in occs) 5 else 0
        if (s.room.isNotBlank() && Regex("""(?i)\b${Regex.escape(s.room)}\b""").containsMatchIn(name)) score += 2
        val days = DAYS.indices.filter { DAYS[it].containsMatchIn(name) }.map { it + 1 }
        if (days.isNotEmpty()) if (s.day in days) score += 1 else return null
        val kind = kind(name)
        if (kind != null) if (kind == kind(s.type)) score += 1 else return null
        s.lecturer?.let { lecturer ->
            val words = lecturer.lowercase().split(Regex("""[^\p{L}]+""")).filter { it.isNotEmpty() && it !in TITLES }
            val initials = words.joinToString("") { it.take(1) }.uppercase()
            if (words.any { it.length >= 3 && Regex("""(?i)\b${Regex.escape(it)}\b""").containsMatchIn(name) } ||
                INITIALS.findAll(name).any { it.value !in NOT_INITIALS && initials.startsWith(it.value) }) score += 2
        }
        return score
    }

    /** The user's activity for class [s]: the only one that can be, else a clear winner. */
    fun pick(activities: List<Activity>, occ: Int, s: Session): Activity? {
        val scored = activities.mapNotNull { a -> score(a.name, occ, s)?.let { a to it } }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        if (scored.size == 1) return best.first
        return best.first.takeIf { best.second >= 2 && best.second > scored[1].second }
    }

    /**
     * Whose [activity] is, given the user's weekly [classes] of its course: the user's when it is
     * picked for one of them; another group's when it names other occurrences, or names one group
     * ([SPECIFIC]) and fits one of the classes but lost to the user's own; unknown otherwise.
     */
    fun owner(activity: Activity, activities: List<Activity>, occ: Int, classes: List<Session>): Owner {
        val occs = occurrences(activity.name)
        if (occs.isNotEmpty() && occ > 0 && occ !in occs) return Owner.OTHER
        val specific = DAYS.any { it.containsMatchIn(activity.name) } || SPECIFIC.containsMatchIn(activity.name)
        var other = false
        for (s in classes) {
            val mine = pick(activities, occ, s) ?: continue
            if (mine == activity) return Owner.MINE
            if (specific && (score(activity.name, occ, s) ?: 0) >= 1) other = true
        }
        return if (other) Owner.OTHER else Owner.UNKNOWN
    }
}
