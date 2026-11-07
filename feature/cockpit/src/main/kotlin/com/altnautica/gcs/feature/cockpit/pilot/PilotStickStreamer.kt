package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.model.PilotInput
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.controls.PadCalibration
import com.altnautica.gcs.core.skills.controls.StickMode
import com.altnautica.gcs.core.skills.controls.padKey
import com.altnautica.gcs.core.skills.controls.radialDeadzone
import com.altnautica.gcs.core.skills.input.GamepadButton
import com.altnautica.gcs.core.skills.input.PadSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Stick conditioning shared by every stick path: the pad's calibration (centre and
 * extents, keyed by [padKey]), a radial dead zone per stick, then the stick mode's
 * mapping to `[roll, pitch, thr, yaw]`. Triggers at or past [triggerPressed] set the
 * L2/R2 bits of the button field. Expo is left to the receiver.
 */
data class StickConditioning(
    val mode: StickMode = StickMode.Mode2,
    val deadzone: Float = DEFAULT_DEADZONE,
    val calibrations: Map<String, PadCalibration> = emptyMap(),
    val triggerPressed: Float = DEFAULT_TRIGGER_PRESSED,
) {
    fun condition(pad: PadSnapshot): PilotInput {
        val cal = calibrations[padKey(pad.vendorId, pad.productId)]
        val a = cal?.normalise(pad.raw) ?: pad.raw
        val (lx, ly) = radialDeadzone(a.leftX, a.leftY, deadzone)
        val (rx, ry) = radialDeadzone(a.rightX, a.rightY, deadzone)
        val axes = mode.axes(a.copy(leftX = lx, leftY = ly, rightX = rx, rightY = ry))
        var buttons = pad.buttons.toLong() and BUTTON_MASK
        if (a.leftTrigger >= triggerPressed) buttons = buttons or (1L shl GamepadButton.L2)
        if (a.rightTrigger >= triggerPressed) buttons = buttons or (1L shl GamepadButton.R2)
        return PilotInput(roll = axes[0], pitch = axes[1], throttle = axes[2], yaw = axes[3], buttons = buttons)
    }

    companion object {
        const val DEFAULT_DEADZONE = 0.08f
        const val DEFAULT_TRIGGER_PRESSED = 0.5f
        private const val BUTTON_MASK = (1L shl (GamepadButton.LAST + 1)) - 1
    }
}

/**
 * Streams the controller's sticks to the pilot channel at [rateHz] while the cockpit is
 * in the foreground, a pad is connected, and the route is the pilot ([sink] non-null).
 * Each stop (pad disconnected, cockpit below STARTED, or the route leaving the pilot)
 * sends `release` once. Android delivers controller input as events, so a stick held
 * still sends nothing: the pad stays live for as long as its device is attached, and
 * its last reported position keeps streaming. A new stick session starts when the
 * cockpit starts, a pad connects or the channel changes.
 */
internal class PilotStickStreamer(
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    /** The pad to stream: the controller, or the on-screen sticks while a finger holds them. */
    private val pad: () -> PadSnapshot,
    private val conditioning: StateFlow<StickConditioning>,
    private val sink: StateFlow<StickSink?>,
    private val rateHz: Int = RATE_HZ,
) {
    private val _streaming = MutableStateFlow(false)

    /** True while input frames go out. */
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private var job: Job? = null
    private var target: StickSink? = null
    private var sessionSink: StickSink? = null
    private var sessionPad: Int? = null

    /** The cockpit reached STARTED: stream while the other conditions hold. */
    fun start() {
        if (job?.isActive == true) return
        sessionSink = null
        sessionPad = null
        job = scope.launch {
            val period = 1_000L / rateHz
            while (isActive) {
                tick()
                delay(period)
            }
        }
    }

    /** The cockpit dropped below STARTED: release and stop. */
    fun stop() {
        job?.cancel()
        job = null
        halt()
    }

    private fun tick() {
        val p = pad()
        val s = sink.value
        val device = p.deviceId
        val fresh = device != null
        if (s == null || !fresh) {
            halt()
            if (device == null) sessionPad = null
            return
        }
        if (s !== target) halt()
        if (s !== sessionSink || device != sessionPad) {
            s.beginSession()
            sessionSink = s
            sessionPad = device
        }
        target = s
        s.input(conditioning.value.condition(p))
        _streaming.value = true
    }

    private fun halt() {
        target?.release()
        target = null
        _streaming.value = false
    }

    companion object {
        const val RATE_HZ = 50
    }
}
