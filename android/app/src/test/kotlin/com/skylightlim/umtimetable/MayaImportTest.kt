package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The parts of the MAYA import that turn UM's SHOUTING names and semester lines into labels. */
class MayaImportTest {

    @Test fun titleCase() {
        val cases = mapOf(
            "COMPUTING MATHEMATICS I" to "Computing Mathematics I",
            "PHILOSOPHY AND CURRENT AFFAIRS" to "Philosophy and Current Affairs",
            "COMPUTER SYSTEMS AND ORGANIZATION" to "Computer Systems and Organization",
            "THE ART OF WAR" to "The Art of War",
            "STATISTICS II" to "Statistics II",
            // UM's own spelling, not "Embrace@umleader".
            "EMBRACE@UMLEADER" to "Embrace@UMLeader",
            "LIFE AT UM" to "Life at UM",
            "LECTURE" to "Lecture",
            "  TUTORIAL " to "Tutorial",
        )
        for ((raw, want) in cases) assertEquals(raw, want, MayaImport.titleCase(raw))
    }

    /** Spectrum's course names, as on 13 Oct 2026, without their code. */
    @Test fun spectrumName() {
        assertEquals("Computing Mathematics I", MayaImport.spectrumName("WIX1001", "WIX1001 COMPUTING MATHEMATICS I"))
        assertEquals("Embrace@UMLeader", MayaImport.spectrumName("GUL1001", "GUL1001 EMBRACE@UMLEADER"))
        assertEquals("Fundamentals of Programming", MayaImport.spectrumName("WIX1002", "WIX1002: FUNDAMENTALS OF PROGRAMMING"))
        assertEquals("Basic Entrepreneurship Enculturation", MayaImport.spectrumName("GIG1003", "gig1003 – BASIC ENTREPRENEURSHIP ENCULTURATION"))
        // Only the code: no name to give.
        assertNull(MayaImport.spectrumName("WIX1002", "WIX1002"))
    }

    private fun term(semester: String, date: String) =
        MayaImport.term(listOf(MayaImport.Event(date, "08:00", "10:00", "WIX1001", "LECTURE", 5, "DK1", semester)))

    @Test fun term() {
        // MAYA's wording for the Semester line hasn't been seen yet; these are the forms accepted.
        for (raw in listOf("S1", "SEM 1", "Semester 1", "1", "SEM-1", "2026/2027 S1", "")) {
            assertEquals(raw, "Sem 1 2026/27", term(raw, "20261012"))
        }
        assertEquals("Sem 2 2026/27", term("SEM 2", "20261012")) // the line wins over the date
        assertEquals("Sem 2 2026/27", term("", "20270315")) // March: Semester 2 of the session that began in 2026
        assertEquals("Sem 1 2027/28", term("", "20271004"))
        assertEquals("Sem 1 2025/26", term("Semester 1 2025/2026", "20261012")) // a year on the line wins
        assertEquals("Special Sem 2026/27", term("SEMESTER KHAS", "20270705"))
    }

    @Test fun rooms() {
        assertEquals("DK2", MayaImport.shortRoom("DEWAN KULIAH 2 FSKTM (280)"))
        assertEquals("MM7", MayaImport.shortRoom("MAKMAL MIKRO 7 FSKTM"))
        assertEquals("DK3 H09", MayaImport.shortRoom("Dewan Kuliah 3 H09"))
        assertEquals("Online", MayaImport.shortRoom("N/A"))
        assertEquals("Online", MayaImport.shortRoom(""))
        assertEquals("Dewan Kuliah 2 FSKTM", MayaImport.roomName("DEWAN KULIAH 2 FSKTM (280)"))
        assertEquals("Online (MAYA lists no room)", MayaImport.roomName("N/A"))
        // TimeEdit's names (13 Oct 2026).
        assertEquals("MM8", MayaImport.shortRoom("MAKMAL GABUNG (MAKMAL MIKRO 8)"))
        assertEquals("Makmal Gabung (Makmal Mikro 8)", MayaImport.roomName("MAKMAL GABUNG (MAKMAL MIKRO 8)"))
        assertEquals("Online", MayaImport.shortRoom("ONLINE LECTURE"))
        assertEquals("Online Lecture", MayaImport.roomName("ONLINE LECTURE"))
        assertEquals("Dewan Kuliah 3 H09", MayaImport.roomName("DEWAN KULIAH 3 H09"))
    }
}
