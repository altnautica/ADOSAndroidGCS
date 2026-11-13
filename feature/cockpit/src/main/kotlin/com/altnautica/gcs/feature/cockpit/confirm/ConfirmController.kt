package com.altnautica.gcs.feature.cockpit.confirm

import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.skills.ConfirmRequest
import com.altnautica.gcs.core.skills.ConfirmResult
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.SkillConfirmer
import com.altnautica.gcs.core.skills.SkillTiming
import com.altnautica.gcs.core.skills.builtin.SkillArgs
import com.altnautica.gcs.core.skills.builtin.TakeoffAltitude
import com.altnautica.gcs.core.skills.builtin.takeoffAltitude
import com.altnautica.gcs.core.skills.input.GamepadButton
import com.altnautica.gcs.feature.cockpit.preflight.PreflightCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/** What the confirm sheet renders. */
data class ConfirmUiState(
    val request: ConfirmRequest,
    /** Takeoff altitude stepper value; null when the skill takes no altitude. */
    val altitudeM: Float?,
    /** Failing preflight checks when the skill is checklist-aware and the checklist is not ready. */
    val failingChecks: List<PreflightCheck>,
    val overrideChecklist: Boolean,
    /** Hold progress 0..1 from any hold source, stepped with [holdRemainingMs] (every 100 ms). */
    val progress: Float,
    /**
     * Guard window left for `guarded` when this state was published, else null. The
     * controller times the lapse itself; this is not republished while nothing shown changes.
     */
    val guardRemainingMs: Long?,
    /** Hold time still needed while a hold runs, rounded up to the tenth of a second the sheet shows, else null. */
    val holdRemainingMs: Long? = null,
) {
    val gesture: ConfirmGesture get() = request.policy.gesture
    val gestureEnabled: Boolean get() = failingChecks.isEmpty() || overrideChecklist
}

/** The guarded tier's countdown line, in seconds to one decimal rounded up so it never reads 0.0 early. */
sealed interface GuardCountdown {
    val seconds: Float

    /** No hold yet: time left to start one before the guard lapses. */
    data class Window(override val seconds: Float) : GuardCountdown

    /** A hold runs: time it still needs before Kill fires. */
    data class Holding(override val seconds: Float) : GuardCountdown
}

private fun tenthsUp(ms: Long): Float = ((ms.coerceAtLeast(0) + 99) / 100) / 10f

/** What the guarded sheet's countdown shows now; null for the other tiers. */
fun ConfirmUiState.guardCountdown(): GuardCountdown? {
    if (gesture != ConfirmGesture.Guarded) return null
    holdRemainingMs?.let { return GuardCountdown.Holding(tenthsUp(it)) }
    return GuardCountdown.Window(tenthsUp(guardRemainingMs ?: 0))
}

/**
 * The cockpit's [SkillConfirmer]. One sheet at a time; touch, the gamepad button that
 * triggered the skill (button A by default) and Enter all feed the same [ConfirmHoldMachine].
 */
class ConfirmController(
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val failingChecks: () -> List<PreflightCheck>,
    private val isButtonHeld: (Int) -> Boolean,
    /** Runs when a sheet resolves confirmed (controller feedback). */
    private val onConfirmed: () -> Unit = {},
) : SkillConfirmer, ConfirmActions {
    private val _state = MutableStateFlow<ConfirmUiState?>(null)
    val state: StateFlow<ConfirmUiState?> = _state.asStateFlow()

    private var pending: CompletableDeferred<ConfirmResult>? = null
    private var machine: ConfirmHoldMachine? = null
    private var ticker: Job? = null
    private var confirmButton = GamepadButton.A

    val isOpen: Boolean get() = pending != null

    override suspend fun confirm(request: ConfirmRequest): ConfirmResult {
        resolve(ConfirmResult.Cancelled)
        val now = clock.nowMs()
        val policy = request.policy
        val holdMs = policy.holdMs.takeIf { it > 0 } ?: when (policy.gesture) {
            ConfirmGesture.Slide -> SkillTiming.SLIDE_HOLD_MS
            ConfirmGesture.Guarded -> SkillTiming.GUARDED_HOLD_MS
            else -> SkillTiming.HOLD_MS
        }
        val failing = if (policy.checklistAware && !request.checklistReady) failingChecks() else emptyList()
        val m = ConfirmHoldMachine(policy.gesture, holdMs, now).also { it.enabled = failing.isEmpty() }
        val deferred = CompletableDeferred<ConfirmResult>()
        pending = deferred
        machine = m
        confirmButton = request.gamepadButton ?: GamepadButton.A
        _state.value = ConfirmUiState(
            request = request,
            altitudeM = if (request.skill.id == "takeoff") takeoffAltitude(request.args) else null,
            failingChecks = failing,
            overrideChecklist = false,
            progress = 0f,
            guardRemainingMs = m.guardRemainingMs(now),
        )
        // The button that opened the sheet is still down: its hold counts.
        if (request.gamepadButton != null && isButtonHeld(request.gamepadButton!!)) m.press(now)
        ticker = scope.launch { tick() }
        return try {
            deferred.await()
        } finally {
            if (pending === deferred) clear()
        }
    }

    private suspend fun tick() {
        while (currentCoroutineContext().isActive) {
            val m = machine ?: return
            val now = clock.nowMs()
            if (m.poll(now)) {
                confirmNow()
                return
            }
            if (m.guardLapsed(now)) {
                resolve(ConfirmResult.Cancelled)
                return
            }
            // The sheet shows the hold in tenths of a second: publish only when that changes.
            // Completion and the guard lapse are timed above at full rate.
            val hold = m.holdRemainingMs(now)?.let { (it + 99) / 100 * 100 }
            val progress = hold?.let { (1f - it.toFloat() / m.holdMs).coerceIn(0f, 1f) } ?: 0f
            _state.update { s ->
                if (s == null || (s.holdRemainingMs == hold && s.progress == progress)) {
                    s
                } else {
                    s.copy(progress = progress, guardRemainingMs = m.guardRemainingMs(now), holdRemainingMs = hold)
                }
            }
            delay(TICK_MS)
        }
    }

    private fun confirmNow() {
        val s = _state.value ?: return
        val args = buildMap<String, Any?> { s.altitudeM?.let { put(SkillArgs.ALTITUDE_M, it) } }
        resolve(ConfirmResult.Confirmed(args, checklistOverridden = s.failingChecks.isNotEmpty() && s.overrideChecklist))
        onConfirmed()
    }

    private fun press() = machine?.press(clock.nowMs())

    private fun release() {
        machine?.release()
        _state.update { it?.copy(progress = 0f, holdRemainingMs = null) }
    }

    /** Drops a hold in progress (the cockpit stopped taking input); the sheet stays open. */
    fun releaseHold() = release()

    /**
     * A gamepad button while the sheet is open: the triggering button holds to confirm,
     * B cancels, Y toggles the checklist override when checks fail, and D-pad up and down
     * step the takeoff altitude by one metre.
     */
    fun onGamepadButton(button: Int, pressed: Boolean) {
        if (!isOpen) return
        if (button == confirmButton) {
            if (pressed) press() else release()
            return
        }
        if (!pressed) return
        val s = _state.value ?: return
        when (button) {
            GamepadButton.B -> cancel()
            GamepadButton.Y -> if (s.failingChecks.isNotEmpty()) setOverride(!s.overrideChecklist)
            GamepadButton.DPAD_UP -> s.altitudeM?.let { setAltitude(it + ALTITUDE_STEP_M) }
            GamepadButton.DPAD_DOWN -> s.altitudeM?.let { setAltitude(it - ALTITUDE_STEP_M) }
        }
    }

    fun onEnter(pressed: Boolean) {
        if (!isOpen) return
        if (pressed) press() else release()
    }

    /** Touch press and release on the sheet's hold control. */
    override fun onTouchHold(pressed: Boolean) {
        if (pressed) press() else release()
    }

    /** The slide control reached its end (touch `slide` tier). */
    override fun onSlideCompleted() {
        val s = _state.value ?: return
        if (s.gesture == ConfirmGesture.Slide && s.gestureEnabled) confirmNow()
    }

    override fun setAltitude(meters: Float) {
        _state.update { s ->
            s?.copy(altitudeM = s.altitudeM?.let { meters.coerceIn(TakeoffAltitude.MIN_M, TakeoffAltitude.MAX_M) })
        }
    }

    override fun setOverride(on: Boolean) {
        machine?.enabled = on || _state.value?.failingChecks.isNullOrEmpty()
        _state.update { it?.copy(overrideChecklist = on, progress = 0f) }
    }

    override fun cancel() = resolve(ConfirmResult.Cancelled)

    private fun resolve(result: ConfirmResult) {
        val d = pending ?: return
        clear()
        d.complete(result)
    }

    private fun clear() {
        pending = null
        machine = null
        ticker?.cancel()
        ticker = null
        _state.value = null
    }

    private companion object {
        const val TICK_MS = 16L
        const val ALTITUDE_STEP_M = 1f
    }
}
