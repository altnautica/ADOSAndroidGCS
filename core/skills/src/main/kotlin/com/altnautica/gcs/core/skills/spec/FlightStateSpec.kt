package com.altnautica.gcs.core.skills.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FlightStateSpec(
    val v: Int,
    val rules: List<FlightStateRuleSpec>,
    @SerialName("fc_mode_classes") val fcModeClasses: FcModeClassesSpec,
    val secondary: FlightStateSecondarySpec,
    @SerialName("hold_reason_labels") val holdReasonLabels: Map<String, String> = emptyMap(),
    /** Labels for the pilot's `position_unverified`, keyed by its labelled `detail`, else its `reason`. */
    @SerialName("position_unverified_labels") val positionUnverifiedLabels: Map<String, String> = emptyMap(),
    /** Words for the pilot's `command.limits` codes; a code with none is not shown. */
    @SerialName("command_limit_labels") val commandLimitLabels: Map<String, String> = emptyMap(),
    @SerialName("stale_after_ms") val staleAfterMs: Long,
    val tap: String,
    @SerialName("mode_sheet") val modeSheet: ModeSheetSpec,
)

/** One chip rule; rules are evaluated in order and the first whose [match] holds wins. */
@Serializable
data class FlightStateRuleSpec(
    val id: String,
    val match: FlightStateMatchSpec,
    val text: String,
    val color: String,
    val dim: Boolean = false,
    val button: String? = null,
    @SerialName("suffix_if_landing_seq") val suffixIfLandingSeq: String? = null,
    /** A button that replaces [button] when its own match also holds. */
    @SerialName("button_if") val buttonIf: ChipButtonIfSpec? = null,
)

@Serializable
data class ChipButtonIfSpec(val match: FlightStateMatchSpec, val button: String)

/** Every non-null field must hold for the rule to match. */
@Serializable
data class FlightStateMatchSpec(
    @SerialName("pilot_stale") val pilotStale: Boolean? = null,
    val owner: String? = null,
    val ready: Boolean? = null,
    @SerialName("mission_state") val missionState: String? = null,
    @SerialName("complete_age_lt_ms") val completeAgeLtMs: Long? = null,
    @SerialName("vtol_transitioning") val vtolTransitioning: Boolean? = null,
    @SerialName("fc_mode_class") val fcModeClass: String? = null,
    @SerialName("control_mode") val controlMode: String? = null,
    /** A launch sequence runs: present, its step not `done`, no error. */
    @SerialName("sequence_running") val sequenceRunning: Boolean? = null,
    @SerialName("fc_failsafe") val fcFailsafe: Boolean? = null,
    /** The pilot reports it does not trust the horizontal position. */
    @SerialName("position_unverified") val positionUnverified: Boolean? = null,
)

@Serializable
data class FcModeClassesSpec(val rtl: FcModeClassSpec, val land: FcModeClassSpec)

/** Numeric custom_mode values per firmware family. */
@Serializable
data class FcModeClassSpec(val copter: List<Int> = emptyList(), val plane: List<Int> = emptyList())

@Serializable
data class FlightStateSecondarySpec(
    val order: List<String>,
    @SerialName("avoid_state") val avoidState: Map<String, SecondaryTextSpec?>,
    @SerialName("hold_reason") val holdReason: SecondaryTextSpec,
    @SerialName("control_mode") val controlMode: Map<String, String>,
    @SerialName("fc_failsafe") val fcFailsafe: SecondaryTextSpec? = null,
    @SerialName("speed_cap") val speedCap: SecondaryTextSpec? = null,
    @SerialName("mission_paused_line") val missionPausedLine: MatchedTextSpec? = null,
    /** `{profile_limit}` of a rule: the horizontal limit the sticks fly at. */
    @SerialName("profile_limit") val profileLimit: KeyedTextSpec? = null,
    val clearance: ClearanceTextSpec? = null,
    @SerialName("input_age") val inputAge: InputAgeTextSpec? = null,
    @SerialName("h_speed") val hSpeed: OwnerTextSpec? = null,
    @SerialName("v_speed") val vSpeed: OwnerTextSpec? = null,
    @SerialName("command_limit") val commandLimit: CommandLimitSpec? = null,
)

/** A part whose text is the `setup-strings` entry [textKey]. */
@Serializable
data class KeyedTextSpec(@SerialName("text_key") val textKey: String)

/** The nearest obstacle, shown under [nearFactor] × the margin; [insideMarginColor] at or inside it. */
@Serializable
data class ClearanceTextSpec(
    @SerialName("text_key") val textKey: String,
    @SerialName("near_factor") val nearFactor: Double,
    @SerialName("near_color") val nearColor: String,
    @SerialName("inside_margin_color") val insideMarginColor: String,
)

/** The stick input age while the sticks fly, shown over [overMs]. */
@Serializable
data class InputAgeTextSpec(@SerialName("text_key") val textKey: String, @SerialName("over_ms") val overMs: Long, val color: String)

/** A part shown only while [owner] owns. */
@Serializable
data class OwnerTextSpec(@SerialName("text_key") val textKey: String, val owner: String, val color: String)

/** A secondary part shown only while its [match] holds. */
@Serializable
data class MatchedTextSpec(val match: FlightStateMatchSpec, val text: String, val color: String)

@Serializable
data class SecondaryTextSpec(val text: String, val color: String)

/**
 * The limit that cut the commanded speed: the first code of the pilot's `command.limits` not in
 * [skip], while one of [owners] flies, worded by [FlightStateSpec.commandLimitLabels]; the
 * [suppressWhileBlocked] codes hide while avoidance reports blocked.
 */
@Serializable
data class CommandLimitSpec(
    val owners: List<String>,
    val skip: List<String>,
    @SerialName("suppress_while_blocked") val suppressWhileBlocked: List<String>,
    val color: String,
    val colors: Map<String, String> = emptyMap(),
)

@Serializable
data class ModeSheetSpec(
    val sections: List<String>,
    val profile: ModeSheetProfileSpec,
    @SerialName("control_mode") val controlMode: ModeSheetControlModeSpec,
    val avoidance: ModeSheetAvoidanceSpec,
    @SerialName("hand_to_fc") val handToFc: ModeSheetHandToFcSpec,
    @SerialName("vtol_transition") val vtolTransition: ModeSheetVtolSpec? = null,
    @SerialName("fc_modes") val fcModes: ModeSheetFcModesSpec,
)

@Serializable
data class ModeSheetProfileSpec(val values: List<String>, val labels: List<String>, val confirm: String)

@Serializable
data class ModeSheetControlModeSpec(
    val values: List<String>,
    val labels: List<String>,
    val explain: Map<String, String>,
    @SerialName("elrs_only_on") val elrsOnlyOn: String,
    val confirm: SpecConfirm,
)

@Serializable
data class ModeSheetAvoidanceSpec(
    val values: List<String>,
    val labels: List<String>,
    @SerialName("confirm_off") val confirmOff: SpecConfirm,
)

@Serializable
data class ModeSheetHandToFcSpec(
    val values: List<String>,
    val labels: List<String>,
    val default: String,
    @SerialName("plane_values") val planeValues: List<String> = emptyList(),
)

@Serializable
data class ModeSheetVtolSpec(
    @SerialName("shown_when") val shownWhen: String,
    @SerialName("to_fw_param1") val toFwParam1: Int,
    @SerialName("to_mc_param1") val toMcParam1: Int,
)

@Serializable
data class ModeSheetFcModesSpec(
    val advanced: Boolean,
    @SerialName("mark_current") val markCurrent: Boolean,
    @SerialName("sets_owner") val setsOwner: String,
)
