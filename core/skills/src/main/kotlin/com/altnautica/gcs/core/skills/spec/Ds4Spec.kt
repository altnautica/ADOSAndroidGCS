package com.altnautica.gcs.core.skills.spec

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Ds4Spec(
    val v: Int,
    @SerialName("default_mode") val defaultMode: String,
    @SerialName("stick_modes") val stickModes: Map<String, Map<String, StickAxisSpec>>,
    val conditioning: ConditioningSpec,
    val stream: StreamSpec,
    @SerialName("centered_threshold") val centeredThreshold: Float,
    val controls: List<String>,
    @SerialName("platform_map") val platformMap: PlatformMapSpec,
    val gestures: Map<String, String> = emptyMap(),
    @SerialName("double_tap_ms") val doubleTapMs: Int,
    val actions: List<ControlActionSpec>,
    @SerialName("action_groups") val actionGroups: List<String>,
    val bindings: List<ControlBindingSpec>,
    @SerialName("locked_actions") val lockedActions: List<LockedActionSpec>,
    @SerialName("when_conditions") val whenConditions: Map<String, String> = emptyMap(),
    val designate: Map<String, String> = emptyMap(),
    val lightbar: LightbarSpec,
    val rumble: RumbleSpec,
    @SerialName("export_format") val exportFormat: JsonObject? = null,
)

/** Which stick and axis feed one flight axis; [invert] flips the platform's sign. */
@Serializable
data class StickAxisSpec(val stick: String, val axis: String, val invert: Boolean)

@Serializable
data class ConditioningSpec(
    @SerialName("radial_deadzone") val radialDeadzone: Float,
    @SerialName("deadzone_min") val deadzoneMin: Float,
    @SerialName("deadzone_max") val deadzoneMax: Float,
    val calibration: String,
    val expo: String,
)

@Serializable
data class StreamSpec(
    @SerialName("rate_hz") val rateHz: Int,
    @SerialName("axes_order") val axesOrder: List<String>,
    @SerialName("stale_timestamp_ms") val staleTimestampMs: Long,
    @SerialName("stop_on") val stopOn: List<String>,
    @SerialName("on_stop") val onStop: String,
    @SerialName("buttons_bitfield") val buttonsBitfield: Map<String, Int>,
    @SerialName("trigger_pressed_threshold") val triggerPressedThreshold: Float,
)

@Serializable
data class PlatformMapSpec(
    @SerialName("web_standard") val webStandard: JsonObject? = null,
    val android: AndroidPlatformSpec,
    @SerialName("linux_evdev") val linuxEvdev: JsonObject? = null,
)

/** Android KeyEvent/MotionEvent constant names for each control. */
@Serializable
data class AndroidPlatformSpec(
    val buttons: Map<String, String>,
    val axes: Map<String, String>,
    @SerialName("trigger_fallback") val triggerFallback: Map<String, String> = emptyMap(),
    @SerialName("y_up_is_negative") val yUpIsNegative: Boolean,
)

@Serializable
data class ControlActionSpec(
    val id: String,
    val label: String,
    val group: String,
    val builtin: String? = null,
)

/**
 * One binding row: [control] (with every control in [with] held) performs [action] on
 * [gesture]. [whenCondition] names a `when_conditions` entry gating the row. Every field
 * but [rateFullDps] is always written, null included; [rateFullDps] is omitted when null.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ControlBindingSpec(
    val control: String,
    val with: List<String>,
    val gesture: String,
    @SerialName("hold_ms") val holdMs: Int?,
    @SerialName("when") val whenCondition: String?,
    val action: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("rate_full_dps") val rateFullDps: Float? = null,
    val rumble: String?,
    val locked: Boolean,
)

@Serializable
data class LockedActionSpec(val action: String, val gesture: String, val combo: Boolean)

@Serializable
data class LightbarSpec(val priority: List<String>, val states: Map<String, LightbarStateSpec>)

@Serializable
data class LightbarStateSpec(val color: String, val pattern: String, val hz: Float? = null)

@Serializable
data class RumbleSpec(
    @SerialName("mode_change") val modeChange: RumblePulsesSpec,
    val warning: RumblePulsesSpec,
    val critical: RumblePulsesSpec,
    val ramp: RumbleRampSpec,
    val countdown: RumbleCountdownSpec,
)

@Serializable
data class RumblePulsesSpec(
    @SerialName("pulses_ms") val pulsesMs: List<Int>,
    @SerialName("gap_ms") val gapMs: Int,
    val strong: Float,
    val weak: Float,
)

@Serializable
data class RumbleRampSpec(val from: Float, val to: Float, val over: String, @SerialName("tick_ms") val tickMs: Int)

@Serializable
data class RumbleCountdownSpec(
    @SerialName("pulse_ms") val pulseMs: Int,
    @SerialName("every_ms") val everyMs: Int,
    val over: String,
)
