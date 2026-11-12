package com.altnautica.gcs.core.skills.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
