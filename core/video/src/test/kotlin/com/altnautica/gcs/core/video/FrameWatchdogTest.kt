package com.altnautica.gcs.core.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameWatchdogTest {

    private var now = 0L
    private val watchdog = FrameWatchdog(VideoClock { now })

    @Test
    fun `no frame yet is connecting not frozen`() {
        now = 60_000
        assertNull(watchdog.check())
    }

    @Test
    fun `frames within 2_5 s are flowing`() {
        watchdog.onFrame()
        now = 2_499
        assertEquals(FrameWatchdog.Verdict.Flowing, watchdog.check())
    }

    @Test
    fun `no frame for 2_5 s is frozen since the last frame`() {
        now = 1_000
        watchdog.onFrame()
        now = 3_500
        assertEquals(FrameWatchdog.Verdict.Frozen(1_000), watchdog.check())
    }

    @Test
    fun `a new frame thaws`() {
        watchdog.onFrame()
        now = 5_000
        watchdog.onFrame()
        assertEquals(FrameWatchdog.Verdict.Flowing, watchdog.check())
    }

    @Test
    fun `backoff doubles from 1 s to a 10 s ceiling and resets`() {
        val b = RedialBackoff()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), List(6) { b.nextDelayMs() })
        b.reset()
        assertEquals(1_000L, b.nextDelayMs())
    }
}
