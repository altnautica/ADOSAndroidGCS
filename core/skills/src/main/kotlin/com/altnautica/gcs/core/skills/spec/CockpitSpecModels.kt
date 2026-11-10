package com.altnautica.gcs.core.skills.spec

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// The cockpit spec shared by every cockpit surface. Field names follow the JSON files
// in `assets/cockpit-spec/` exactly; every file is an object with a top-level "v": 1.

/** Confirm tier of a built-in or a Mode-sheet control. */
@Serializable
enum class SpecConfirmKind {
    @SerialName("tap") Tap,
    @SerialName("hold") Hold,
    @SerialName("hold_confirm") HoldConfirm,
}

@Serializable
data class SpecConfirm(val kind: SpecConfirmKind, val ms: Int = 0)

@Serializable
enum class SpecArmRequirement {
    @SerialName("any") Any,
    @SerialName("armed") Armed,
    @SerialName("disarmed") Disarmed,
    @SerialName("armed_landed") ArmedLanded,
    @SerialName("disarmed_or_landed") DisarmedOrLanded,
}

/** What a built-in does while the link is stale: refuse with a reason, or send anyway. */
@Serializable
enum class SpecStaleLink {
    @SerialName("disable") Disable,
    @SerialName("send_blind") SendBlind,
}

// ---- builtins.json -------------------------------------------------------------------

@Serializable
data class BuiltinsSpec(
    val v: Int,
    @SerialName("arm_requirements") val armRequirements: Map<String, String> = emptyMap(),
    @SerialName("confirm_kinds") val confirmKinds: Map<String, String> = emptyMap(),
    val builtins: List<BuiltinSpec>,
    val removed: List<String> = emptyList(),
    @SerialName("profile_keys") val profileKeys: Map<String, String> = emptyMap(),
    @SerialName("fc_mode_presets") val fcModePresets: String? = null,
)

@Serializable
data class BuiltinSpec(
    val id: String,
    val label: String,
    val icon: String,
    @SerialName("arm_requirement") val armRequirement: SpecArmRequirement,
    val confirm: SpecConfirm,
    @SerialName("needs_fresh_link") val needsFreshLink: Boolean,
    @SerialName("stale_link") val staleLink: SpecStaleLink,
    val keyboard: String? = null,
    @SerialName("arms_first") val armsFirst: Boolean = false,
    @SerialName("pilot_action") val pilotAction: String,
    val notes: String? = null,
)

// ---- ds4.json ------------------------------------------------------------------------

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

// ---- layout.json ---------------------------------------------------------------------

@Serializable
data class LayoutSpec(
    val v: Int,
    val breakpoints: BreakpointsSpec,
    @SerialName("touch_target") val touchTarget: TouchTargetSpec,
    val zones: List<ZoneSpec>,
    val classes: Map<String, LayoutClassSpec>,
    @SerialName("on_screen_sticks") val onScreenSticks: OnScreenSticksSpec,
    val banners: BannersSpec,
    /** Screen orientation locks by surface, e.g. `android_flight_screen` to `landscape`. */
    @SerialName("orientation_lock") val orientationLock: Map<String, String> = emptyMap(),
    /** `none`: the flight screen draws no status band, telemetry strip, navigation rail or hint bar. */
    @SerialName("legacy_chrome") val legacyChrome: String = "none",
    @SerialName("status_pill") val statusPill: StatusPillLayoutSpec? = null,
    val battery: BatteryLayoutSpec? = null,
    /** Confirm-hold durations by action (`take_control`, `hand_to_fc`, `vtol_transition`, `fly_here`, `stop`). */
    @SerialName("holds_ms") val holdsMs: Map<String, Int> = emptyMap(),
    @SerialName("map_long_press_ms") val mapLongPressMs: Int? = null,
    val launch: LaunchLayoutSpec? = null,
    val menu: MenuLayoutSpec? = null,
    /** Pointer to the contextual tips file. */
    val tips: String? = null,
)

@Serializable
data class StatusPillLayoutSpec(
    @SerialName("storage_warn_pct") val storageWarnPct: Int? = null,
    @SerialName("storage_crit_pct") val storageCritPct: Int? = null,
)

@Serializable
data class BatteryLayoutSpec(@SerialName("low_fallback_pct") val lowFallbackPct: Int? = null)

@Serializable
data class LaunchLayoutSpec(
    @SerialName("alt_min_m") val altMinM: Float,
    @SerialName("alt_max_m") val altMaxM: Float,
    @SerialName("alt_default_m") val altDefaultM: Float,
)

@Serializable
data class MenuLayoutSpec(val order: List<String> = emptyList())

@Serializable
data class BreakpointsSpec(
    val measure: Map<String, String> = emptyMap(),
    val classes: List<WidthClassSpec>,
    @SerialName("kiosk_forces") val kioskForces: String,
    val portrait: String,
)

/** Width class [id] covers [min]..[max] dp inclusive; a null [max] is unbounded. */
@Serializable
data class WidthClassSpec(val id: String, val min: Int, val max: Int? = null)

@Serializable
data class TouchTargetSpec(
    val min: Int,
    @SerialName("left_column") val leftColumn: Int,
    val shutter: Int,
    val unit: String,
)

@Serializable
data class ZoneSpec(
    val id: String,
    val items: List<String> = emptyList(),
    val align: String? = null,
    val cards: List<String> = emptyList(),
    val switch: List<String> = emptyList(),
    val battery: ZoneBatterySpec? = null,
    val overflow: ZoneOverflowSpec? = null,
    /** Items that leave the zone first when it is too short (right column). */
    @SerialName("drop_order") val dropOrder: List<String> = emptyList(),
)

@Serializable
data class ZoneOverflowSpec(
    @SerialName("to_menu_order") val toMenuOrder: List<String> = emptyList(),
    @SerialName("never_move") val neverMove: List<String> = emptyList(),
)

@Serializable
data class ZoneBatterySpec(val show: List<String>)

@Serializable
data class LayoutClassSpec(
    val orientation: String,
    @SerialName("column_width") val columnWidth: Int? = null,
    @SerialName("bottom_left_card") val bottomLeftCard: BottomLeftCardSpec? = null,
    val labels: String,
    @SerialName("default_layer_level") val defaultLayerLevel: String,
    val scale: Float,
    @SerialName("focus_rings") val focusRings: String? = null,
    @SerialName("overlay_fps_cap") val overlayFpsCap: Int? = null,
    val stack: List<String> = emptyList(),
)

@Serializable
data class BottomLeftCardSpec(val kind: String, val size: Int? = null, val width: Int? = null, val height: Int? = null)

@Serializable
data class OnScreenSticksSpec(
    val setting: String,
    val default: Boolean,
    val classes: List<String>,
    @SerialName("pad_size") val padSize: Int,
    val placement: String,
    @SerialName("pushes_inward") val pushesInward: List<String>,
)

@Serializable
data class BannersSpec(
    @SerialName("handover_ms") val handoverMs: Long,
    @SerialName("low_battery_rth_countdown_s") val lowBatteryRthCountdownS: Int,
)

// ---- layers.json ---------------------------------------------------------------------

@Serializable
data class LayersSpec(
    val v: Int,
    val layers: List<LayerSpec>,
    @SerialName("plugin_layer") val pluginLayer: PluginLayerSpec,
    val levels: LevelsSpec,
    @SerialName("level_controls") val levelControls: LevelControlsSpec,
    val staleness: LayerStalenessSpec,
    @SerialName("view_lease") val viewLease: ViewLeaseSpec,
    @SerialName("vision_demand") val visionDemand: VisionDemandSpec,
    val options: LayerOptionsSpec,
    @SerialName("grid_decode") val gridDecode: GridDecodeSpec,
    val persistence: PersistenceSpec,
    val interpolation: InterpolationSpec,
)

@Serializable
data class LayerSpec(
    val id: String,
    val label: String,
    val default: Boolean,
    val source: String,
    val lease: Boolean,
    val z: Int,
    val kind: String? = null,
    val groups: List<String>? = null,
)

@Serializable
data class PluginLayerSpec(
    @SerialName("id_pattern") val idPattern: String,
    val default: Boolean,
    val source: String,
    val lease: Boolean,
    val z: Int,
)

@Serializable
data class LevelsSpec(
    val order: List<String>,
    val full: LevelSpec,
    val route: LevelSpec,
    val off: LevelSpec,
    @SerialName("lease_layers_independent") val leaseLayersIndependent: Boolean,
    val note: String? = null,
)

/**
 * One layer level. [layers] is a list of layer ids, or for `full` a sentence meaning
 * "every layer whose default is true"; [layerIds] reads the list form.
 */
@Serializable
data class LevelSpec(
    val layers: JsonElement,
    @SerialName("ar_route_roles") val arRouteRoles: Map<String, List<String>> = emptyMap(),
    val always: List<String> = emptyList(),
)

@Serializable
data class LevelControlsSpec(
    val keyboard: String,
    val gamepad: List<String>,
    @SerialName("top_bar_button") val topBarButton: Boolean,
)

@Serializable
data class LayerStalenessSpec(
    @SerialName("dim_after_ms") val dimAfterMs: Long,
    @SerialName("hide_after_ms") val hideAfterMs: Long,
    @SerialName("unavailable_chip") val unavailableChip: String,
)

@Serializable
data class ViewLeaseSpec(
    val message: JsonObject,
    @SerialName("renew_ms") val renewMs: Long,
    @SerialName("expires_ms") val expiresMs: Long,
    @SerialName("poll_query") val pollQuery: String,
    @SerialName("release_when") val releaseWhen: List<String>,
)

@Serializable
data class VisionDemandSpec(val route: String, val body: JsonObject, @SerialName("renew_ms") val renewMs: Long)

@Serializable
data class LayerOptionsSpec(
    val depth: DepthOptionsSpec,
    val obstacles: ObstacleOptionsSpec,
    val surfaces: SurfaceOptionsSpec,
)

@Serializable
data class DepthOptionsSpec(
    val style: StringChoiceSpec,
    @SerialName("range_m") val rangeM: RangeChoiceSpec,
)

@Serializable
data class StringChoiceSpec(val values: List<String>, val default: String)

/** A choice of ranges in metres; a null value means Off. */
@Serializable
data class RangeChoiceSpec(val values: List<Float?>, val labels: List<String>, val default: Float? = null)

@Serializable
data class ObstacleOptionsSpec(
    @SerialName("near_max_m") val nearMaxM: Float,
    @SerialName("mid_max_m") val midMaxM: Float,
    val measured: String,
    val inferred: String,
    val unknown: String,
    @SerialName("arc_strip") val arcStrip: String,
    @SerialName("radar_card_source") val radarCardSource: String,
)

@Serializable
data class SurfaceOptionsSpec(val stroke: String, @SerialName("score_chip") val scoreChip: Boolean)

@Serializable
data class GridDecodeSpec(
    val cols: Int,
    val rows: Int,
    @SerialName("unknown_code") val unknownCode: Int,
    val formula: String,
    @SerialName("range_m") val rangeM: List<Float>,
    @SerialName("measured_bit") val measuredBit: Int,
)

@Serializable
data class PersistenceSpec(val scope: List<String>, val keys: List<String>)

@Serializable
data class InterpolationSpec(
    @SerialName("fade_in_ms") val fadeInMs: Long,
    @SerialName("fade_out_ms") val fadeOutMs: Long,
    @SerialName("key_field") val keyField: String,
    @SerialName("pip_draws_layers") val pipDrawsLayers: Boolean,
)

// ---- views.json ----------------------------------------------------------------------

@Serializable
data class ViewsSpec(
    val v: Int,
    val views: List<ViewSpec>,
    val default: String,
    val cycle: List<String>,
    @SerialName("cycle_keys") val cycleKeys: CycleKeysSpec,
    val streams: StreamsSpec,
    val dashboard: DashboardSpec? = null,
)

@Serializable
data class ViewSpec(
    val id: String,
    val label: String,
    val main: String,
    val secondary: String? = null,
    @SerialName("side_panel") val sidePanel: List<String>? = null,
    /** The split view's draggable divider; null for views without one. */
    val divider: DividerSpec? = null,
    /** Where the video goes when the map is the main view (`bottom_left_card`). */
    @SerialName("video_slot") val videoSlot: String? = null,
    @SerialName("forced_layers") val forcedLayers: List<String> = emptyList(),
    @SerialName("forced_while") val forcedWhile: String? = null,
)

/** A draggable divider: fraction of the width given to the main pane, [default] within [min]..[max]. */
@Serializable
data class DividerSpec(
    val draggable: Boolean = false,
    val default: Float = 0.5f,
    val min: Float = 0f,
    val max: Float = 1f,
    val remember: String? = null,
)

@Serializable
data class CycleKeysSpec(val keyboard: String, val gamepad: GestureRefSpec)

@Serializable
data class GestureRefSpec(val control: String, val gesture: String)

@Serializable
data class StreamsSpec(val orthogonal: Boolean, val select: StreamSelectSpec)

@Serializable
data class StreamSelectSpec(
    val tabs: Boolean,
    @SerialName("digit_keys") val digitKeys: Boolean,
    @SerialName("next_key") val nextKey: String,
)

@Serializable
data class DashboardSpec(
    val component: String,
    val level: String,
    @SerialName("read_only") val readOnly: Boolean,
    val poll: DashboardPollSpec,
)

@Serializable
data class DashboardPollSpec(val route: String, val hz: Float)

// ---- flight-state.json ---------------------------------------------------------------

@Serializable
data class FlightStateSpec(
    val v: Int,
    val rules: List<FlightStateRuleSpec>,
    @SerialName("fc_mode_classes") val fcModeClasses: FcModeClassesSpec,
    val secondary: FlightStateSecondarySpec,
    @SerialName("hold_reason_labels") val holdReasonLabels: Map<String, String> = emptyMap(),
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
)

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
)

@Serializable
data class SecondaryTextSpec(val text: String, val color: String)

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

// ---- tokens.json ---------------------------------------------------------------------

/** Colours are `#RRGGBB` or `#RRGGBBAA`; [SpecColor.argb] converts them. */
@Serializable
data class TokensSpec(
    val v: Int,
    val ar: Map<String, String>,
    @SerialName("ar_geometry") val arGeometry: Map<String, JsonElement> = emptyMap(),
    val layer: Map<String, JsonElement> = emptyMap(),
    val state: Map<String, String>,
    val lightbar: Map<String, String>,
    val controller: Map<String, String> = emptyMap(),
    val chrome: ChromeTokensSpec = ChromeTokensSpec(),
)

/** The flight-screen panel look: translucent blurred panels, hairline border, type scale and spacing. */
@Serializable
data class ChromeTokensSpec(
    @SerialName("panel_bg") val panelBg: String = "#080A0E8C",
    @SerialName("panel_bg_no_blur") val panelBgNoBlur: String = "#080A0ED1",
    @SerialName("panel_blur_px") val panelBlurPx: Float = 16f,
    val border: String = "#FFFFFF1F",
    @SerialName("border_px") val borderPx: Float = 1f,
    @SerialName("radius_px") val radiusPx: Float = 12f,
    @SerialName("radius_small_px") val radiusSmallPx: Float = 8f,
    val text: String = "#F5F7FA",
    @SerialName("text_dim") val textDim: String = "#FFFFFFA3",
    @SerialName("severity_ok") val severityOk: String = "#00C853",
    @SerialName("severity_warning") val severityWarning: String = "#F59E0B",
    @SerialName("severity_critical") val severityCritical: String = "#EF4444",
    @SerialName("stop_bg") val stopBg: String = "#EF4444",
    @SerialName("stop_text") val stopText: String = "#FFFFFF",
    val type: ChromeTypeSpec = ChromeTypeSpec(),
    @SerialName("icon_px") val iconPx: Float = 18f,
    @SerialName("gap_px") val gapPx: Float = 8f,
    @SerialName("pad_px") val padPx: Float = 10f,
    @SerialName("edge_inset_px") val edgeInsetPx: Float = 8f,
)

/** The flight-screen type scale, in CSS px (one px is one dp on Android). */
@Serializable
data class ChromeTypeSpec(
    @SerialName("top_bar_px") val topBarPx: Float = 13f,
    @SerialName("top_bar_line_px") val topBarLinePx: Float = 15f,
    @SerialName("value_px") val valuePx: Float = 16f,
    @SerialName("label_px") val labelPx: Float = 11f,
    @SerialName("chip_px") val chipPx: Float = 13f,
    @SerialName("chip_weight") val chipWeight: Int = 600,
)

// ---- controller art and hotspots -----------------------------------------------------

/**
 * A controller drawing: SVG path data in a [viewBox] of `[minX, minY, width, height]`,
 * drawn stroked only with [strokeWidth] in viewBox units.
 */
@Serializable
data class ControllerArtSpec(
    val v: Int,
    val viewBox: List<Float>,
    @SerialName("stroke_width") val strokeWidth: Float,
    @SerialName("stroke_linecap") val strokeLinecap: String,
    @SerialName("stroke_linejoin") val strokeLinejoin: String,
    val paths: List<ArtPathSpec>,
)

@Serializable
enum class ArtRole {
    @SerialName("body") Body,
    @SerialName("control") Control,
    @SerialName("detail") Detail,
}

/** One drawn path; [control] names the control it belongs to, for pressed-state fills. */
@Serializable
data class ArtPathSpec(val id: String, val d: String, val role: ArtRole, val control: String? = null)

@Serializable
enum class HotspotShape {
    @SerialName("circle") Circle,
    @SerialName("rect") Rect,
    @SerialName("path") Path,
}

@Serializable
enum class LabelSide {
    @SerialName("left") Left,
    @SerialName("right") Right,
    @SerialName("top") Top,
    @SerialName("bottom") Bottom,
}

/**
 * Hotspot geometry in art coordinates: a circle sets [cx], [cy], [r]; a rect sets [x],
 * [y], [w], [h] and optionally [rx]; a path sets [d].
 */
@Serializable
data class HotspotGeomSpec(
    val cx: Float? = null,
    val cy: Float? = null,
    val r: Float? = null,
    val x: Float? = null,
    val y: Float? = null,
    val w: Float? = null,
    val h: Float? = null,
    val rx: Float? = null,
    val d: String? = null,
)

/** The touch target and label placement of one control, in the [ControllerHotspotsSpec.viewBox]. */
@Serializable
data class HotspotSpec(
    val shape: HotspotShape,
    val geom: HotspotGeomSpec,
    @SerialName("label_anchor") val labelAnchor: List<Float>,
    @SerialName("label_side") val labelSide: LabelSide,
)

/** Hotspots keyed by control id (`stick_l`, `cross`, `dpad_up`, `lightbar`, …). */
@Serializable
data class ControllerHotspotsSpec(
    val v: Int,
    val viewBox: List<Float>,
    val hotspots: Map<String, HotspotSpec>,
)

/** Setup wizard strings keyed by their dotted key; `{arg}` placeholders interpolate. */
@Serializable
data class SetupStringsSpec(
    val v: Int,
    val locale: String,
    val strings: Map<String, String>,
) {
    /**
     * The string for [key] with each `{name}` replaced from [args]. A missing body variant
     * (`setup.<step>.body.<variant>`) falls back to the step's `setup.<step>.body`; any other
     * missing key reads as the key itself, so a gap shows rather than a blank.
     */
    fun format(key: String, args: Map<String, String> = emptyMap()): String {
        var text = strings[key] ?: SETUP_BODY_VARIANT.matchEntire(key)?.let { strings[it.groupValues[1]] } ?: return key
        for ((name, value) in args) text = text.replace("{$name}", value)
        return text
    }
}

private val SETUP_BODY_VARIANT = Regex("""^(setup\.[^.]+\.body)\.[^.]+$""")

// ---- tips.json -----------------------------------------------------------------------

/** Contextual tips on the flight screen: one at a time, ground only. */
@Serializable
data class TipsSpec(
    val v: Int,
    val rules: TipsRulesSpec,
    val tips: List<TipSpec>,
)

@Serializable
data class TipsRulesSpec(
    @SerialName("max_visible") val maxVisible: Int = 1,
    /** Facts that must each equal their value for any tip to show (`armed`, `airborne`, `flight_screen`, ...). */
    @SerialName("show_only_when") val showOnlyWhen: Map<String, Boolean>,
    @SerialName("first_delay_ms") val firstDelayMs: Long,
    @SerialName("gap_between_ms") val gapBetweenMs: Long,
    @SerialName("auto_dismiss_ms") val autoDismissMs: Long,
    @SerialName("dismiss_on") val dismissOn: List<String> = emptyList(),
    @SerialName("never_cover_zones") val neverCoverZones: List<String> = emptyList(),
    val kiosk: String? = null,
    val persist: String? = null,
    @SerialName("reset_setting_key") val resetSettingKey: String? = null,
)

/** One tip: shown beside [anchor] once [trigger] fired; text key `tips.<id>`. */
@Serializable
data class TipSpec(val id: String, val anchor: String, val trigger: String, val order: Int)
