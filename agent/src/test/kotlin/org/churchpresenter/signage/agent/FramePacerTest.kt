package org.churchpresenter.signage.agent

import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A page's frames reach the screen at no more than its rate, and at all a slow machine draws. */
class FramePacerTest {
    /** How many of a second's frames, drawn [drawnFps] a second, get through at [fps]. */
    private fun shownInASecond(drawnFps: Double, fps: Int): Int {
        val pacer = FramePacer(fps)
        // A second already running, so the first frame's start does not count.
        val start = 10_000.0
        (0 until (drawnFps * 2).toInt()).forEach { pacer.take((start - 2_000 + it * 1_000 / drawnFps).roundToLong()) }
        return (0 until drawnFps.toInt()).count { pacer.take((start + it * 1_000 / drawnFps).roundToLong()) }
    }

    @Test
    fun aFastMachineIsHeldToTheRate() {
        assertEquals(30, shownInASecond(60.0, 30))
        assertEquals(15, shownInASecond(60.0, 15))
    }

    @Test
    fun aSlowMachineShowsAllItDraws() {
        // A Raspberry Pi draws about 23 a second: all of them at 30, not every second one.
        assertEquals(23, shownInASecond(23.0, 30))
        assertEquals(15, shownInASecond(23.0, 15), "still 15, though its frames do not line up with 15")
    }

    @Test
    fun theFirstFrameGoesAndOneTooSoonDoesNot() {
        val pacer = FramePacer(15)
        assertTrue(pacer.take(1_000))
        assertFalse(pacer.take(1_020))
        assertTrue(pacer.take(1_067))
    }

    @Test
    fun aPauseDoesNotReleaseABurst() {
        val pacer = FramePacer(30)
        assertTrue(pacer.take(0))
        assertTrue(pacer.take(5_000))
        assertFalse(pacer.take(5_010), "the frames missed in the pause are not owed")
    }
}
