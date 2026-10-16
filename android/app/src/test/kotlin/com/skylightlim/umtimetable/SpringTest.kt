package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's spring: from 0 to rest at 1, starting at the speed it's given (as a move let go by the finger). */
class SpringTest {
    private fun speedAtStart(s: Motion.Spring): Float {
        val dt = 0.001f // seconds
        return s.getInterpolation(dt * 1000f / s.duration) / dt
    }

    @Test fun fromRestToRest() {
        for (s in listOf(Motion.Spring(0.78f, 0.38f), Motion.Spring(0.92f, 0.42f), Motion.Spring(1f, 0.5f))) {
            assertEquals(0f, s.getInterpolation(0f), 1e-4f)
            assertEquals(1f, s.getInterpolation(1f), 0f)
            assertEquals(1f, s.getInterpolation(0.999f), 0.006f) // settled to within half a percent
            assertEquals(0f, speedAtStart(s), 0.5f)
        }
    }

    @Test fun startsAtTheSpeedGiven() {
        // Let go at 3 distances a second: it leaves at that speed instead of from a standstill.
        val s = Motion.Spring(0.92f, 0.42f, 3f)
        assertEquals(3f, speedAtStart(s), 0.15f)
        assertEquals(1f, s.getInterpolation(1f), 0f)
        assertEquals(1f, s.getInterpolation(0.999f), 0.006f)
    }

    @Test fun littleOvershoot() {
        val bouncy = Motion.Spring(0.78f, 0.38f)
        val peak = (0..1000).maxOf { bouncy.getInterpolation(it / 1000f) }
        assertTrue("peak $peak", peak in 1.01f..1.04f) // about 2%, and back without swinging
    }
}
