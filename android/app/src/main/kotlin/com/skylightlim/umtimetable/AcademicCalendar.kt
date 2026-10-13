package com.skylightlim.umtimetable

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * UM's bachelor-degree academic calendar, to say which week of the semester a week is. From the
 * 2026/2027 calendar as amended by the Senate on 22 July 2026 (aasd.um.edu.my, "Attachment D
 * Academic Calendar Bachelor Degree 2026-2027 Academic Session_July 2026.pdf"). Add the next
 * session's calendar when UM publishes it; outside these dates a week has no semester week.
 */
object AcademicCalendar {
    enum class Kind { TEACHING, BREAK, REVISION, EXAMS }

    /** A week of the semester: teaching week [n] of [of], or the mid-semester break, revision or exams. */
    data class Week(val kind: Kind, val n: Int = 0, val of: Int = 0)

    private class Period(val kind: Kind, val start: LocalDate, val weeks: Int)

    private fun p(kind: Kind, start: String, weeks: Int) = Period(kind, LocalDate.parse(start), weeks)

    private val SEMESTERS = listOf(
        "Sem 1 2026/27" to listOf(
            p(Kind.TEACHING, "2026-10-05", 8), p(Kind.BREAK, "2026-11-30", 1), p(Kind.TEACHING, "2026-12-07", 6),
            p(Kind.REVISION, "2027-01-18", 1), p(Kind.EXAMS, "2027-01-25", 2),
        ),
        "Sem 2 2026/27" to listOf(
            p(Kind.TEACHING, "2027-03-01", 7), p(Kind.BREAK, "2027-04-19", 1), p(Kind.TEACHING, "2027-04-26", 7),
            p(Kind.REVISION, "2027-06-14", 1), p(Kind.EXAMS, "2027-06-21", 2),
        ),
        "Special sem 2026/27" to listOf(p(Kind.TEACHING, "2027-07-19", 7), p(Kind.EXAMS, "2027-09-06", 1)),
    )

    /** The semester week [date] falls in, or null outside the calendar's semesters. */
    fun week(date: LocalDate): Week? {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        for ((_, semester) in SEMESTERS) {
            val total = semester.filter { it.kind == Kind.TEACHING }.sumOf { it.weeks }
            var taught = 0
            for (period in semester) {
                val k = ChronoUnit.WEEKS.between(period.start, monday)
                if (k in 0 until period.weeks) {
                    return if (period.kind == Kind.TEACHING) Week(Kind.TEACHING, taught + k.toInt() + 1, total) else Week(period.kind)
                }
                if (period.kind == Kind.TEACHING) taught += period.weeks
            }
        }
        return null
    }

    /** The semester [date] is in, or the next one to start ("Sem 1 2026/27"); null past the calendar. */
    fun semesterName(date: LocalDate): String? =
        SEMESTERS.firstOrNull { (_, periods) -> periods.last().let { it.start.plusWeeks(it.weeks.toLong()) } > date }?.first
}
