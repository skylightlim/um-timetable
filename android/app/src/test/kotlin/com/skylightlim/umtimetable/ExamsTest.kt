package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Reading exams off MAYA's timetable page (SIW_XTTB, task list). The markup is MAYA's as seen on 16 Nov 2026,
 * blank lines and all; the student, courses, dates and halls are made up.
 */
class ExamsTest {

    private fun day(date: String, events: String) =
        """<div class="sv-row sitsjqttdaylistrow" data-jqtt-date="${date}00000000" data-jqtt-month-name="February 2027"><div class="sv-col-xs-2 sv-col-sm-1 sitsjqttlistviewdate" aria-hidden="true"><div class="sitsjqttdatenumber">1</div><div class="sitsjqttlistviewdatename">Mon</div></div><div class="sv-col-xs-10 sv-col-sm-11">""" +
            (if (events.isEmpty()) "<p>No events to display</p>" else "<ul>$events</ul>") + "</div></div>"

    private fun exam(time: String, code: String, room: String, occ: Int, barred: String = "N/A", special: String = "N/A") =
        """<li class="sitsjqtteventcontainer sitsjqttlistitem" data-arrayid="0" data-eventid="YTTB1" data-linkid=""><div class="sitsjqtteventpanel sitsjqttfocusable sv-panel sv-panel-default" style="border-color:mediumseagreen" tabindex="0" role="button" data-jqtt-events-added="Y"><div class="sitsjqttitemtext sitsjqttitemcontent"><span class="sv-sr-only">Activate to access event details</span><div class="sv-panel-body"><div class="sv-row"><span class="sv-sr-only">Monday 01/Feb/2027</span><div class="sv-col-xs-12">$time</div><div class="sv-col-xs-12">



<br><b>Module:</b> $code<br>
<b>Semester</b>: Semester 1<br>
<b>Activity:</b> Centrally Scheduled Exam<br><b>Room: </b>$room<br>
<b>Site:
</b>
UM<br><b>Occurence: </b>$occ<br>
<b>Barred from Exam: </b> $barred<br>
<b>Quarantine Exam: </b> N/A




<br><b>Special Case: </b>$special<br><b style="color:red">Please arrive at least 15 minutes before the examination starts.</b></div></div></div></div></div></li>"""

    /** A class on the same page, as MAYA writes those: other labels, and not an exam. */
    private val lecture =
        """<li class="sitsjqtteventcontainer sitsjqttlistitem" data-arrayid="1" data-eventid="YTTB2" data-linkid=""><div class="sv-panel-body"><div class="sv-col-xs-12">10:00 - 12:00</div><div class="sv-col-xs-12">
<b>Occurrence:</b> 3<br>
<b>Code:</b> GIG1017<br>
<b>Semester:</b> SEMESTER 1<br>
<b>Activity:</b> LECTURE<br>
<b>Campus:</b> UNIVERSITI MALAYA KUALA LUMPUR<br>
<b>Room:</b> Dewan Kuliah 4 FSKTM (200)</div></div></li>"""

    private val page = "<html><body>" +
        day("20270201", exam("09:00 - 11:00", "GIG1017", "Dewan 4 Aras 2 Bangunan Peperiksaan", 3) + lecture) +
        day("20270202", "") +
        day("20270203", exam("14:30 - 17:30", "WIX1001", "Dewan 1 Aras 1 Bangunan Peperiksaan", 5, special = "Extra time &amp; own room")) +
        day("20270204", exam("08:30 - 10:30", "GUL1001", "Dewan Kuliah 2 FSKTM", 11, barred = "Yes")) +
        "</body></html>"

    @Test fun readsTheExamsOnly() {
        val exams = Exams.read(page)
        assertEquals(3, exams.size)
        val first = exams[0]
        assertEquals(LocalDate.of(2027, 2, 1), first.date)
        assertEquals(9 * 60, first.start)
        assertEquals(11 * 60, first.end)
        assertEquals("GIG1017", first.code)
        assertEquals(3, first.occ)
        assertEquals("Dewan 4 Aras 2 Bangunan Peperiksaan", first.room)
        assertEquals(emptyList<String>(), first.flags)
        assertEquals(15, first.early)
        assertEquals("be there by 08:45", first.note)
        assertEquals("Dewan 4", first.shortRoom)
    }

    @Test fun flagsWhatMayaFillsIn() {
        val (_, extra, barred) = Exams.read(page)
        assertEquals(listOf("Special Case: Extra time & own room"), extra.flags)
        assertEquals(listOf("Barred from Exam: Yes"), barred.flags)
        // Being barred says more than when to be there.
        assertEquals("Barred from Exam: Yes", barred.note)
        // Not in the exam building: named as a class's room is.
        assertEquals("DK2", barred.shortRoom)
    }

    @Test fun datesShown() {
        val days = Exams.days(page)!!
        assertEquals(LocalDate.of(2027, 2, 1), days.start)
        assertEquals(LocalDate.of(2027, 2, 4), days.endInclusive)
        assertNull(Exams.days("<html><body>Log in to the portal</body></html>"))
        assertEquals(emptyList<Exam>(), Exams.read(day("20270202", "")))
    }

    @Test fun mayaDates() {
        assertEquals("18/Jan/2027", Exams.mayaDate(LocalDate.of(2027, 1, 18)))
        assertEquals("07/Feb/2027", Exams.mayaDate(LocalDate.of(2027, 2, 7)))
    }

    @Test fun savedAndReadBack() {
        val e = Exams.read(page)[1]
        assertEquals(e, Exam.from(e.json()))
    }
}
