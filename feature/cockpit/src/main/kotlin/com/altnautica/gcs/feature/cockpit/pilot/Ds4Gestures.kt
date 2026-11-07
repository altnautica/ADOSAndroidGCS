package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.skills.controls.Gesture
import com.altnautica.gcs.core.skills.spec.ControlBindingSpec
import kotlin.math.abs

/** What the gesture engine produced on one update. */
internal sealed interface GestureOut {
    /** A binding row fired; [held] when it fired as a completed hold. */
    data class Fire(val row: ControlBindingSpec, val held: Boolean) : GestureOut

    /** An analog row's value (0..1) changed. */
    data class Analog(val row: ControlBindingSpec, val value: Float) : GestureOut

    /** A rumble cue: `mode_change` on a firing row, `ramp` (at [level]) or `countdown` ticks during a hold. */
    data class Buzz(val kind: String, val level: Float) : GestureOut
}

/** A hold in progress, for the hold ring. */
data class ControllerHold(val action: String, val progress: Float)

/**
 * The controller binding engine: turns pressed controls into tap, hold, double-tap,
 * chord and analog gestures per the binding rows.
 *
 * - A chord row (`with` non-empty) matches while its other controls are held; the most
 *   specific matching row wins, and a control used as a chord modifier never taps.
 * - Taps fire on release, so a hold or a chord on the same control can claim it; rows
 *   whose action is in [immediateActions] (the brake) fire on press instead.
 * - A hold fires after `hold_ms` with every control of its chord held; a multi-control
 *   hold that is let go early swallows the taps of its controls.
 * - A tap whose control also has an enabled double-tap row waits [doubleTapMs] for the
 *   second tap.
 * - `stick_l_centered` must hold for the whole hold; every other condition is read
 *   through [condition] when the gesture starts and when it fires.
 */
internal class Ds4Gestures(
    private val doubleTapMs: Long,
    private val centeredThreshold: Float,
    private val rampTickMs: Long,
    private val rampFrom: Float,
    private val rampTo: Float,
    private val countdownEveryMs: Long,
    private val immediateActions: Set<String> = IMMEDIATE,
) {
    private class Hold(val row: ControlBindingSpec, val startMs: Long, var lastBuzzMs: Long)
    private class Pending(val row: ControlBindingSpec?, val atMs: Long)

    private var prev: Set<String> = emptySet()
    private val pressedAt = HashMap<String, Long>()
    private val holds = ArrayList<Hold>()
    private val consumed = HashSet<String>()
    private val firedOnPress = HashSet<String>()
    private val modifiers = HashSet<String>()
    private val pending = HashMap<String, Pending>()
    private val secondPress = HashSet<String>()
    private val analog = HashMap<ControlBindingSpec, Float>()

    /** The hold closest to firing, or null. */
    var hold: ControllerHold? = null
        private set

    /** Clears every gesture in progress (the cockpit stopped). */
    fun reset() {
        prev = emptySet()
        pressedAt.clear()
        holds.clear()
        consumed.clear()
        firedOnPress.clear()
        modifiers.clear()
        pending.clear()
        secondPress.clear()
        analog.clear()
        hold = null
    }

    fun update(
        nowMs: Long,
        rows: List<ControlBindingSpec>,
        pressed: Set<String>,
        analogValues: Map<String, Float>,
        leftStick: Pair<Float, Float>,
        condition: (String) -> Boolean,
    ): List<GestureOut> {
        val out = ArrayList<GestureOut>()
        val centred = abs(leftStick.first) < centeredThreshold && abs(leftStick.second) < centeredThreshold
        fun ok(row: ControlBindingSpec) = when (val w = row.whenCondition) {
            null -> true
            STICK_L_CENTERED -> centred
            else -> condition(w)
        }
        fun fire(row: ControlBindingSpec, held: Boolean) {
            out += GestureOut.Fire(row, held)
            if (row.rumble == RUMBLE_MODE_CHANGE) out += GestureOut.Buzz(RUMBLE_MODE_CHANGE, 1f)
            modifiers += row.with
        }

        val newly = pressed - prev
        val released = prev - pressed

        for (c in newly) {
            pressedAt[c] = nowMs
            pending[c]?.let { p ->
                if (nowMs - p.atMs <= doubleTapMs) {
                    secondPress += c
                    pending.remove(c)
                }
            }
            // Press rows fire on the way down; a hold row on the same control keeps timing.
            (best(rows, c, Gesture.PRESS, pressed) { ok(it) } ?: best(rows, c, Gesture.TAP, pressed) { it.action in immediateActions && ok(it) })?.let {
                fire(it, held = false)
                firedOnPress += c
            }
            for (row in rows) {
                if (row.gesture != Gesture.HOLD || c !in row.controls() || !pressed.containsAll(row.controls()) || !ok(row)) continue
                if (holds.none { it.row == row }) holds += Hold(row, nowMs, nowMs)
                if (row.with.isNotEmpty()) consumed += row.controls()
            }
        }

        val iter = holds.iterator()
        var closest: ControllerHold? = null
        while (iter.hasNext()) {
            val h = iter.next()
            val row = h.row
            if (!pressed.containsAll(row.controls()) || (row.whenCondition == STICK_L_CENTERED && !centred)) {
                iter.remove()
                continue
            }
            val total = (row.holdMs ?: 0).toLong()
            val progress = if (total <= 0) 1f else ((nowMs - h.startMs).toFloat() / total).coerceAtMost(1f)
            if (progress >= 1f) {
                iter.remove()
                if (ok(row)) {
                    fire(row, held = true)
                    consumed += row.controls()
                }
                continue
            }
            when (row.rumble) {
                RUMBLE_RAMP -> if (nowMs - h.lastBuzzMs >= rampTickMs) {
                    h.lastBuzzMs = nowMs
                    out += GestureOut.Buzz(RUMBLE_RAMP, rampFrom + (rampTo - rampFrom) * progress)
                }
                RUMBLE_COUNTDOWN -> if (nowMs - h.lastBuzzMs >= countdownEveryMs) {
                    h.lastBuzzMs = nowMs
                    out += GestureOut.Buzz(RUMBLE_COUNTDOWN, 1f)
                }
            }
            if (closest == null || progress > closest.progress) closest = ControllerHold(row.action, progress)
        }
        // A chord or a fired hold that claimed a control ends single-control holds on it.
        holds.removeAll { h -> h.row.with.isEmpty() && h.row.control in consumed }
        hold = closest?.takeIf { c -> holds.any { it.row.action == c.action } }

        for (c in released) {
            val heldMs = nowMs - (pressedAt.remove(c) ?: nowMs)
            val second = secondPress.remove(c)
            when {
                consumed.remove(c) -> continue
                firedOnPress.remove(c) -> continue
                modifiers.remove(c) -> continue
            }
            val longest = rows.filter { r -> r.gesture == Gesture.HOLD && r.control == c && r.with.isEmpty() }.mapNotNull { r -> r.holdMs }.maxOrNull()
            if (longest != null && heldMs >= longest) continue
            val tap = best(rows, c, Gesture.TAP, pressed) { r -> ok(r) }
            val double = best(rows, c, Gesture.DOUBLE_TAP, pressed) { r -> ok(r) }
            when {
                second && double != null -> fire(double, held = false)
                double != null -> pending[c] = Pending(tap, nowMs)
                tap != null -> fire(tap, held = false)
            }
        }
        // Consumed controls stay consumed until released; drop marks of controls already up.
        consumed.retainAll(pressed)
        modifiers.retainAll(pressed)

        val expired = pending.filterValues { p -> nowMs - p.atMs > doubleTapMs }
        for ((c, p) in expired) {
            pending.remove(c)
            p.row?.let { fire(it, held = false) }
        }

        for (row in rows) {
            if (row.gesture != Gesture.ANALOG) continue
            val v = (analogValues[row.control] ?: 0f).coerceIn(0f, 1f)
            val last = analog[row]
            if (last == null || abs(v - last) >= ANALOG_STEP || (v == 0f && last != 0f) || (v == 1f && last != 1f)) {
                analog[row] = v
                if (last != null || v != 0f) out += GestureOut.Analog(row, v)
            }
        }

        prev = pressed
        return out
    }

    /** The most specific row on [control] with [gesture] whose modifiers are all held. */
    private inline fun best(
        rows: List<ControlBindingSpec>,
        control: String,
        gesture: String,
        pressed: Set<String>,
        accept: (ControlBindingSpec) -> Boolean,
    ): ControlBindingSpec? = rows
        .filter { it.control == control && it.gesture == gesture && pressed.containsAll(it.with) && accept(it) }
        .maxByOrNull { it.with.size }

    private fun ControlBindingSpec.controls(): List<String> = with + control

    companion object {
        const val STICK_L_CENTERED = "stick_l_centered"
        const val RUMBLE_MODE_CHANGE = "mode_change"
        const val RUMBLE_RAMP = "ramp"
        const val RUMBLE_COUNTDOWN = "countdown"
        private const val ANALOG_STEP = 0.02f

        /** The brake fires on press: it is safe to repeat and must not wait for a release. */
        val IMMEDIATE: Set<String> = setOf("pause", "rth_cancel")
    }
}
