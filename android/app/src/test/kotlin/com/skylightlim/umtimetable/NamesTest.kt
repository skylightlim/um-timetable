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
