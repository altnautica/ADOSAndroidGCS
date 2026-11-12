package com.altnautica.gcs.core.skills.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
