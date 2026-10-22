package com.skylightlim.umtimetable

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The web screens keep a page in the app only on the sites it trusts, by whole domain names. */
class HostInTest {
    @Test fun theDomainAndItsSubdomains() {
        assertTrue(hostIn("um.edu.my", "um.edu.my"))
        assertTrue(hostIn("spectrum.um.edu.my", "um.edu.my"))
        assertTrue(hostIn("login.microsoftonline.com", "live.com", "microsoftonline.com"))
        assertTrue(hostIn("Login.MicrosoftOnline.com.", "microsoftonline.com")) // case and a trailing dot
    }

    @Test fun notLookalikes() {
        assertFalse(hostIn("evilmicrosoft.com", "microsoft.com"))
        assertFalse(hostIn("notum.edu.my", "um.edu.my"))
        assertFalse(hostIn("spectrum.um.edu.my.example.com", "um.edu.my"))
        assertFalse(hostIn("", "um.edu.my"))
        assertFalse(hostIn(null, "um.edu.my"))
    }
}
