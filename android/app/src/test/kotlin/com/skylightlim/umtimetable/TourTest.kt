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

    @Test fun everyStopOpensAPageAndShowsOneThing() {
        val pages = setOf("day", "week", "due", "files", "course")
        assertTrue(Tour.STOPS.all { it.page in pages && it.title.isNotBlank() && it.body.isNotBlank() })
        // Each act once, in the order they're met, and Settings isn't one of them.
        assertEquals(Tour.Act.entries.toList(), Tour.STOPS.map { it.act })
        assertTrue(Tour.STOPS.none { it.page == "settings" })
    }

    @Test fun theFingerComesActsAndGoes() {
        for (g in Tour.Gesture.entries - Tour.Gesture.LOOK) {
            val end = Tour.length(g)
            // Not there at the start or the end of a go, so one go runs into the next without a jump.
            assertEquals(0f, Tour.pose(g, 0).shown, 0.001f)
            assertEquals(0f, Tour.pose(g, end).shown, 0.001f)
            // Pressed at some point, and let go before it fades.
            assertTrue((0..end step 10).any { Tour.pose(g, it).down > 0.99f })
            assertEquals(0f, Tour.pose(g, end - 300).down, 0.001f)
            // What it does happens while it's down: a tap's touch, a hold full, a swipe's end, a drag's lift.
            assertTrue(Tour.pose(g, Tour.actAt(g)).down > 0.5f || g == Tour.Gesture.SWIPE_UP)
        }
        // A look shows no finger.
        assertEquals(0f, Tour.pose(Tour.Gesture.LOOK, 1000).shown, 0.001f)
        // A hold's ring fills; a swipe goes from below the spot to above it; a drag ends away from where it began.
        assertEquals(1f, Tour.pose(Tour.Gesture.HOLD, 1200).hold, 0.001f)
        assertTrue(Tour.pose(Tour.Gesture.SWIPE_UP, 400).dy > 0.9f && Tour.pose(Tour.Gesture.SWIPE_UP, 1100).dy < -0.9f)
        assertTrue(Tour.pose(Tour.Gesture.DRAG, 1800).let { it.dx > 0.5f && it.dy > 0.7f })
    }
}
