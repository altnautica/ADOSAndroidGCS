package com.altnautica.gcs.core.skills.controls

import com.altnautica.gcs.core.skills.binding.BindingConflict
import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.core.skills.spec.ControlBindingSpec
import java.util.Locale
import kotlin.math.hypot
import kotlinx.serialization.Serializable

/** Gesture names of a binding row, as written in `ds4.json` and the export file. */
object Gesture {
    const val TAP = "tap"

    /** Fires when the control goes down; a hold row on the same control keeps timing and fires on top. */
    const val PRESS = "press"
    const val HOLD = "hold"
    const val DOUBLE_TAP = "double_tap"
    const val ANALOG = "analog"

    /** The gestures the controls editor offers per control, in row order. */
    val EDITABLE: List<String> = listOf(PRESS, TAP, HOLD, DOUBLE_TAP)

    /** Hold time given to a new hold row: 1.5 s. */
    const val DEFAULT_HOLD_MS = 1_500
}

/** The action id that leaves a gesture unbound; never stored as a row. */
const val ACTION_NONE = "none"

/** Transmitter stick mode: Mode 2 puts throttle on the left stick, Mode 1 on the right. */
enum class StickMode(val wire: String) {
    Mode1("mode1"),
    Mode2("mode2"),
    ;

    /** Pilot axes `[roll, pitch, thr, yaw]` from conditioned stick positions (Y positive up). */
    fun axes(a: GamepadAxes): FloatArray = when (this) {
        Mode2 -> floatArrayOf(a.rightX, a.rightY, a.leftY, a.leftX)
        Mode1 -> floatArrayOf(a.rightX, a.leftY, a.rightY, a.leftX)
    }

    companion object {
        fun fromWire(value: String?): StickMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Whether the bindings are the shipped map or the operator's own. */
enum class ControlsProfile(val wire: String) {
    AdosDefault("ados_default"),
    User("user"),
}

/** Which drawing and export `pad` value a controller uses. */
enum class PadKind(val wire: String) {
    Ds4("ds4"),
    Generic("generic"),
}

/** One stick axis as the pad reports it: resting [centre] and the travel [min]..[max]. */
@Serializable
data class AxisCalibration(val centre: Float = 0f, val min: Float = -1f, val max: Float = 1f) {
    /** Maps [raw] so the centre reads 0 and each extent reads ±1. */
    fun normalise(raw: Float): Float {
        val span = if (raw >= centre) max - centre else centre - min
        if (span <= MIN_SPAN) return 0f
        return ((raw - centre) / span).coerceIn(-1f, 1f)
    }

    private companion object {
        const val MIN_SPAN = 1e-3f
    }
}

/** Per-pad stick calibration: centre and extents of both sticks. Triggers pass through. */
@Serializable
data class PadCalibration(
    val lx: AxisCalibration = AxisCalibration(),
    val ly: AxisCalibration = AxisCalibration(),
    val rx: AxisCalibration = AxisCalibration(),
    val ry: AxisCalibration = AxisCalibration(),
) {
    fun normalise(raw: GamepadAxes): GamepadAxes = raw.copy(
        leftX = lx.normalise(raw.leftX),
        leftY = ly.normalise(raw.leftY),
        rightX = rx.normalise(raw.rightX),
        rightY = ry.normalise(raw.rightY),
    )
}

/** Calibration key of a pad: `vvvv:pppp`, lower-case hex vendor and product id. */
fun padKey(vendorId: Int, productId: Int): String = "%04x:%04x".format(Locale.ROOT, vendorId and 0xFFFF, productId and 0xFFFF)

/**
 * Radial dead zone: a stick within [deadzone] of centre reads (0, 0); beyond it the
 * remaining travel is rescaled so full deflection still reads 1, keeping the direction.
 */
fun radialDeadzone(x: Float, y: Float, deadzone: Float): Pair<Float, Float> {
    val m = hypot(x, y)
    if (m <= deadzone || m == 0f) return 0f to 0f
    val scaled = ((m.coerceAtMost(1f) - deadzone) / (1f - deadzone)) / m
    return (x * scaled) to (y * scaled)
}

/**
 * Every operator controls preference. [bindings] is the complete effective binding
 * list (stored overrides or the shipped map, with plugin skill seeds) and [conflicts]
 * the rows in it that fire on the same press.
 */
data class ControlsState(
    val bindings: List<ControlBindingSpec>,
    val conflicts: List<BindingConflict>,
    val profile: ControlsProfile,
    val stickMode: StickMode,
    val deadzone: Float,
    val calibrations: Map<String, PadCalibration>,
    val onScreenSticks: Boolean,
    val tourSeen: Boolean,
)
