package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeDismissTest {
    private val density = 3f // an xxhdpi phone
    private val width = 1200 // a Due row, px

    @Test fun theLineIsAThirdOfTheRowButNeverShort() {
        assertEquals(400f, SwipeDismiss.line(width, density), 0.001f)
        // A narrow row still needs a real swipe, not a nudge.
        assertEquals(SwipeDismiss.MIN_DP * density, SwipeDismiss.line(300, density), 0.001f)
    }

    @Test fun pastTheLineOrAFlickLeftDismisses() {
        assertTrue(SwipeDismiss.goes(-401f, 0f, width, density))
        assertTrue(SwipeDismiss.goes(-60f, -3000f, width, density))
        // Short and slow springs back, and so does a flick right after going left, or one brought back home.
        assertFalse(SwipeDismiss.goes(-300f, -100f, width, density))
        assertFalse(SwipeDismiss.goes(-300f, 3000f, width, density))
        assertFalse(SwipeDismiss.goes(0f, -3000f, width, density))
    }
}
