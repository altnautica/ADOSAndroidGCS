package com.altnautica.gcs.core.video

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/** Monotonic milliseconds; swapped for a fake in tests. */
fun interface VideoClock {
    fun nowMs(): Long

    companion object {
        val Elapsed = VideoClock { SystemClock.elapsedRealtime() }
    }
}

/**
 * Counts decoded frames and says when the picture has stopped moving.
 *
 * [onFrame] is called from the render or decode thread; [check] from the
 * session's supervisor. A session that has never produced a frame is not
 * frozen, it is still connecting.
 */
class FrameWatchdog(
    private val clock: VideoClock,
    private val freezeAfterMs: Long = FREEZE_AFTER_MS,
) {
    private val lastFrameMs = AtomicLong(NO_FRAME)
    private val frames = AtomicLong(0)

    val frameCount: Long get() = frames.get()

    fun onFrame() {
        frames.incrementAndGet()
        lastFrameMs.set(clock.nowMs())
    }

    fun reset() {
        lastFrameMs.set(NO_FRAME)
    }

    /** Null while no frame has arrived; else whether frames flow, and since when they stopped. */
    fun check(): Verdict? {
        val last = lastFrameMs.get()
        if (last == NO_FRAME) return null
        return if (clock.nowMs() - last >= freezeAfterMs) Verdict.Frozen(last) else Verdict.Flowing
    }

    sealed interface Verdict {
        data object Flowing : Verdict
        data class Frozen(val sinceMs: Long) : Verdict
    }

    companion object {
        const val FREEZE_AFTER_MS = 2_500L
        private const val NO_FRAME = Long.MIN_VALUE
    }
}

/** Re-dial delays: 1 s doubling to a 10 s ceiling, reset after a good session. */
class RedialBackoff(
    private val initialMs: Long = 1_000L,
    private val maxMs: Long = 10_000L,
) {
    private var next = initialMs

    fun nextDelayMs(): Long {
        val d = next
        next = (next * 2).coerceAtMost(maxMs)
        return d
    }

    fun reset() {
        next = initialMs
    }
}
