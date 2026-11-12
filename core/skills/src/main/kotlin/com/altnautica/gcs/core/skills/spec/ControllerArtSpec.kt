package com.altnautica.gcs.core.skills.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
