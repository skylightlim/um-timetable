package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lecturer names from TimeEdit (in TimeEdit's format; the names are made up), course codes and occurrence groups from Spectrum. */
class NamesTest {

    @Test fun timeEditDisplayName() {
        assertEquals("Assoc. Prof. Dr. Daniel bin Ismail", TimeEditLookup.displayName("ASSOCIATE PROF. DR.", "DANIEL BIN ISMAIL"))
        assertEquals("Assoc. Prof. Dr. Tan Wei Ming", TimeEditLookup.displayName("PROF. MADYA DR.", "TAN WEI MING"))
        assertEquals("Prof. Ts. Dr. Kamal Ariffin bin Ma Arof", TimeEditLookup.displayName("PROF. TS. DR.", "KAMAL ARIFFIN BIN MA AROF"))
        assertEquals("Aina binti Hassan", TimeEditLookup.displayName("", "AINA  BINTI HASSAN"))
    }

    @Test fun samePerson() {
        assertTrue(TimeEditLookup.samePerson("Dr. Aina", "Dr. Aina binti Hassan"))
        // TimeEdit drops apostrophes: "MA AROF" for Ma'arof.
        assertTrue(TimeEditLookup.samePerson("Prof. Ts. Dr. Kamal Ariffin bin Ma'arof", "PROF. TS. DR. KAMAL ARIFFIN BIN MA AROF"))
        assertFalse(TimeEditLookup.samePerson("Dr. Farid Osman", "Dr. Hafiz Iskandar"))
        // Titles alone aren't a match.
        assertFalse(TimeEditLookup.samePerson("Dr.", "Dr. Hafiz Iskandar"))
        assertFalse(TimeEditLookup.samePerson("", "Dr. Hafiz Iskandar"))
    }

    /** Spectrum's occurrence groups, in the format seen on 13 Oct 2026. */
    /** Course-wide announcements, shaped like Spectrum's (subjects and greetings made up), for a student in OCC 12. */
    @Test fun announcementsForOtherOccurrences() {
        assertTrue(SpectrumSync.addressedToOthers("Online Tutorials and Lab Sessions for OCC7, OCC8, and OCC9 Until Further Notice", "Dear OCC7, OCC8, and OCC9 Students, …", 12))
        assertTrue(SpectrumSync.addressedToOthers("Important Announcement", "Dear OCC 3 students, the tutorial moves to Friday.", 12))
        assertFalse(SpectrumSync.addressedToOthers("Replacement class for OCC 9 and 12", "", 12))
        assertFalse(SpectrumSync.addressedToOthers("Important Announcement", "Dear OCC 12 students, the tutorial moves to Friday.", 12))
        assertFalse(SpectrumSync.addressedToOthers("Week 2: no physical class", "Use the slides and videos. OCC 3 will meet on Friday.".padStart(200, ' '), 12))
        assertFalse(SpectrumSync.addressedToOthers("Lab 3 is released", "Submit by Friday.", 12))
        assertFalse(SpectrumSync.addressedToOthers("Tutorial for OCC 3", "", 0)) // occurrence unknown: keep it
    }

    @Test fun occurrenceOf() {
        assertEquals(10, SpectrumSync.occurrenceOf(org.json.JSONObject().put("name", "GROUP 10 2026/2027_1").put("idnumber", "2026@2027@1@10")))
        assertEquals(11, SpectrumSync.occurrenceOf(org.json.JSONObject().put("name", "GROUP 11 2026/2027_1").put("idnumber", "")))
        assertNull(SpectrumSync.occurrenceOf(org.json.JSONObject().put("name", "Lecturers").put("idnumber", "")))
    }

    @Test fun courseCode() {
        assertEquals("WIX1001", Spectrum.courseCode("WIX1001", "WIX1001 COMPUTING MATHEMATICS I"))
        assertEquals("GIG1003", Spectrum.courseCode(null, "GIG 1003 BASIC ENTREPRENEURSHIP ENCULTURATION"))
        assertEquals("GUL1001", Spectrum.courseCode("UM-2026-S1", "GUL1001 EMBRACE@UMLEADER"))
        assertNull(Spectrum.courseCode("General", null))
    }
}
