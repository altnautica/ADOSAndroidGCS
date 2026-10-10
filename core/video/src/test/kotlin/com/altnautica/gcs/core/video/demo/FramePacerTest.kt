package com.altnautica.gcs.core.video.demo

import org.junit.Assert.assertEquals
import org.junit.Test

class FramePacerTest {

    private val period = 1000.0 / 30

    @Test
    fun `deadlines stay on the 30 fps grid however long each frame takes`() {
        val pacer = FramePacer(startMs = 1_000, periodMs = period)
        // Frame 0 drew in 10 ms: frame 1 is due at 1033.3 ms.
        assertEquals(24L, pacer.delayAfterFrame(1_010))
        // Frame 1 drew in 20 ms: frame 2 is due at 1066.7 ms.
        assertEquals(13L, pacer.delayAfterFrame(1_054))
        // Frames 3..30, each drawn in 5 ms: frame 30 lands one second after the start,
        // not 30 x (draw + period) later.
        var now = 1_067L
        repeat(28) { now += 5 + pacer.delayAfterFrame(now + 5) }
        assertEquals(2_000.0, now.toDouble(), 1.0)
    }

    @Test
    fun `a late frame skips the deadlines it missed`() {
        val pacer = FramePacer(startMs = 0, periodMs = period)
        // Frame 0 took 101 ms: deadlines 33.3, 66.7 and 100.0 are gone; the next is 133.3.
        assertEquals(33L, pacer.delayAfterFrame(101))
        // Back on the grid: the next deadline is 166.7 ms.
        assertEquals(32L, pacer.delayAfterFrame(135))
    }

    @Test
    fun `a frame finishing exactly on a deadline waits for the next one`() {
        val pacer = FramePacer(startMs = 0, periodMs = 40.0)
        assertEquals(40L, pacer.delayAfterFrame(40))
        assertEquals(40L, pacer.delayAfterFrame(80))
    }
}
