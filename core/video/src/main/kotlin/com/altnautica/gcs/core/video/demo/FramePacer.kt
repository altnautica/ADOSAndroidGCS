package com.altnautica.gcs.core.video.demo

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Fixed-rate frame deadlines: frame n is due at `startMs + n * periodMs`, so the
 * rate holds however long each frame takes to draw. A frame that finishes past
 * one or more deadlines skips them and waits for the next one still ahead.
 */
internal class FramePacer(private val startMs: Long, private val periodMs: Double) {
    private var frame = 0L

    /** Milliseconds to wait, after a frame finished at [nowMs], until the next deadline. */
    fun delayAfterFrame(nowMs: Long): Long {
        frame++
        if (deadline(frame) <= nowMs) frame = floor((nowMs - startMs) / periodMs).toLong() + 1
        return ceil(deadline(frame) - nowMs).toLong().coerceAtLeast(0)
    }

    private fun deadline(n: Long): Double = startMs + n * periodMs
}
