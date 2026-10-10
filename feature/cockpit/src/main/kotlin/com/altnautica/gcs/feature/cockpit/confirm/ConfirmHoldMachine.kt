package com.altnautica.gcs.feature.cockpit.confirm

import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.skills.SkillTiming

/**
 * Hold timing of a confirm sheet for hardware inputs (gamepad button, Enter) and
 * for the hold ring on touch. `hold` completes after [holdMs] of continuous press;
 * `slide` completes after [holdMs] (1500 ms) when held instead of slid; `guarded`
 * arms its guard when the sheet opens and fires on a [holdMs] hold that starts
 * inside the [guardWindowMs] window, otherwise the guard lapses.
 */
class ConfirmHoldMachine(
    val gesture: ConfirmGesture,
    val holdMs: Int,
    openedAtMs: Long,
    private val guardWindowMs: Long = SkillTiming.GUARD_WINDOW_MS,
) {
    private val guardArmedAt: Long? = if (gesture == ConfirmGesture.Guarded) openedAtMs else null
    private var pressedAt: Long? = null
    private var done = false

    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) pressedAt = null
        }

    val holding: Boolean get() = pressedAt != null

    /** True once the guard window has passed with no hold in progress. */
    fun guardLapsed(nowMs: Long): Boolean {
        val armed = guardArmedAt ?: return false
        return !done && pressedAt == null && nowMs - armed > guardWindowMs
    }

    /** Milliseconds left in the guard window, or null for other tiers. */
    fun guardRemainingMs(nowMs: Long): Long? = guardArmedAt?.let { (it + guardWindowMs - nowMs).coerceAtLeast(0) }

    fun press(nowMs: Long) {
        if (!enabled || done || pressedAt != null || gesture == ConfirmGesture.Tap) return
        if (guardLapsed(nowMs)) return
        pressedAt = nowMs
    }

    fun release() {
        if (!done) pressedAt = null
    }

    /** Hold fraction 0..1. */
    fun progress(nowMs: Long): Float {
        if (done) return 1f
        val start = pressedAt ?: return 0f
        return ((nowMs - start).toFloat() / holdMs).coerceIn(0f, 1f)
    }

    /** True exactly once, when the hold completes. */
    fun poll(nowMs: Long): Boolean {
        if (done) return false
        val start = pressedAt ?: return false
        if (nowMs - start >= holdMs) {
            done = true
            return true
        }
        return false
    }
}
