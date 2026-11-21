package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TourTest {
    @Test fun aNewUserSeesItOnceAndAnAppInUseHasSeenIt() {
        // First run: the sign-in poster is up, so the tour waits for the sign-in.
        assertEquals(Tour.PENDING, Tour.onLaunch(null, firstRun = true))
        // Signed in since, the app relaunched before the tour came: still to come.
        assertEquals(Tour.PENDING, Tour.onLaunch(Tour.PENDING, firstRun = false))
        // In use before the tour came (an update): seen, so it isn't sprung on them.
        assertEquals(Tour.SEEN, Tour.onLaunch(null, firstRun = false))
        // Seen (or skipped) stays seen; Settings shows it again.
        assertEquals(Tour.SEEN, Tour.onLaunch(Tour.SEEN, firstRun = false))
        assertEquals(Tour.SEEN, Tour.onLaunch(Tour.SEEN, firstRun = true))
    }

    @Test fun everyStopOpensAPageAndTheEndsStandAlone() {
        val pages = setOf("day", "week", "due", "files", "settings")
        assertTrue(Tour.STOPS.all { it.page in pages && it.title.isNotBlank() && it.body.isNotBlank() })
        assertEquals(Tour.Target.NONE, Tour.STOPS.first().target)
        assertEquals(Tour.Target.NONE, Tour.STOPS.last().target)
        // Each tab gets its stop.
        val lit = Tour.STOPS.map { it.target }.toSet()
        assertTrue(lit.containsAll(Tour.Target.entries))
        // The last card says where to find it again.
        assertTrue(Tour.STOPS.last().body.contains("Settings"))
    }
}
