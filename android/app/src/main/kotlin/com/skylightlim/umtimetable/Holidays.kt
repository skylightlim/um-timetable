package com.skylightlim.umtimetable

import java.time.LocalDate

/**
 * Public holidays in Kuala Lumpur (UM's campus), from the federal and state schedules the Prime
 * Minister's Department publishes (kabinet.gov.my/hari-kelepasan-am: HKA-2026.pdf, HKA_2027.pdf; the
 * W.P. Kuala Lumpur column), from September 2026, when the 2026/2027 session's dates begin
 * ([AcademicCalendar]). Spectrum's calendar has none: only course events.
 *
 * A holiday on a Sunday makes the Monday after it one too (Holidays Act 1951, s. 3; the Public Service
 * Department applied it in February 2026); those are listed as replacement days. Not listed: 8 June
 * 2027, after Awal Muharram on Sunday the 6th, since the 7th is already a holiday and whether the rule
 * then moves on to the Tuesday is disputed. [Holiday.tentative] ones depend on the moon (or Deepavali's
 * calendar) and may move. Add the next year's when the schedule is published (each August).
 */
object Holidays {
    data class Holiday(val date: LocalDate, val name: String, val replacement: Boolean = false, val tentative: Boolean = false) {
        /** "Deepavali", "Deepavali (replacement)". */
        val label get() = if (replacement) "$name (replacement)" else name
    }

    private fun h(date: String, name: String, tentative: Boolean = false) = Holiday(LocalDate.parse(date), name, tentative = tentative)
    private fun after(date: String, name: String) = Holiday(LocalDate.parse(date), name, replacement = true)

    val ALL = listOf(
        // 2026
        h("2026-09-16", "Malaysia Day"),
        h("2026-11-08", "Deepavali", tentative = true), after("2026-11-09", "Deepavali"),
        h("2026-12-25", "Christmas"),
        // 2027
        h("2027-01-01", "New Year's Day"),
        h("2027-01-22", "Thaipusam"),
        h("2027-02-01", "Federal Territory Day"),
        h("2027-02-06", "Chinese New Year"), h("2027-02-07", "Chinese New Year"), after("2027-02-08", "Chinese New Year"),
        h("2027-02-24", "Nuzul Al-Quran"),
        h("2027-03-10", "Hari Raya Aidilfitri", tentative = true), h("2027-03-11", "Hari Raya Aidilfitri", tentative = true),
        h("2027-05-01", "Labour Day"),
        h("2027-05-17", "Hari Raya Haji", tentative = true),
        h("2027-05-20", "Wesak Day"),
        h("2027-06-06", "Awal Muharram"),
        h("2027-06-07", "Agong's Birthday"),
        h("2027-08-15", "Maulidur Rasul"), after("2027-08-16", "Maulidur Rasul"),
        h("2027-08-31", "National Day"),
        h("2027-09-16", "Malaysia Day"),
        h("2027-10-28", "Deepavali", tentative = true),
        h("2027-12-25", "Christmas"),
    )

    private val byDate = ALL.associateBy { it.date }

    /** The holiday on [date], if it's one. */
    fun on(date: LocalDate): Holiday? = byDate[date]
}
