package com.altnautica.gcs.core.skills.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
