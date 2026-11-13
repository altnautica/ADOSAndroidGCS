package com.altnautica.gcs.core.skills.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    /** Top bar: the flight-state chip's width cap. */
    val chip: ZoneChipSpec? = null,
    /** Top bar: the link cell's text and icon colour bands. */
    val link: ZoneLinkSpec? = null,
)

@Serializable
data class ZoneOverflowSpec(
    @SerialName("to_menu_order") val toMenuOrder: List<String> = emptyList(),
    @SerialName("never_move") val neverMove: List<String> = emptyList(),
)

@Serializable
data class ZoneBatterySpec(
    val show: List<String>,
    @SerialName("time_to_reserve") val timeToReserve: TimeToReserveSpec? = null,
)

/** Battery time to the RTL reserve: [text] in full, [short] when the bar is short of room (string keys). */
@Serializable
data class TimeToReserveSpec(val text: String, val short: String)

/** The chip never takes more than [maxFrac] of the top bar's width; the rest ellipsizes. */
@Serializable
data class ZoneChipSpec(@SerialName("max_frac") val maxFrac: Float)

/** The link cell: the newest vehicle frame's age as [text], [never] before any frame (string keys). */
@Serializable
data class ZoneLinkSpec(
    val metric: String,
    val text: String,
    val never: String,
    @SerialName("icon_color") val iconColor: LinkIconColorSpec,
    @SerialName("age_format") val ageFormat: LinkAgeFormatSpec = LinkAgeFormatSpec(),
)

/**
 * `{s}` for the link text: the age in seconds with [decimals] places below [decimalsBelowS],
 * else whole seconds rounded half up. [cases] are `[age_ms, text]` golden rows.
 */
@Serializable
data class LinkAgeFormatSpec(
    @SerialName("decimals_below_s") val decimalsBelowS: Int = 10,
    val decimals: Int = 1,
    val cases: List<kotlinx.serialization.json.JsonArray> = emptyList(),
)

/** Below [okBelowMs] the link icon is ok, below [warningBelowMs] warning, else critical. */
@Serializable
data class LinkIconColorSpec(
    @SerialName("ok_below_ms") val okBelowMs: Long,
    @SerialName("warning_below_ms") val warningBelowMs: Long,
)

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
