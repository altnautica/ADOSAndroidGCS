package com.altnautica.gcs.feature.cockpit.hud.layers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire models of the navigation-stream overlay kinds the agent computes: `ar`,
 * `obstacles`, `depth` and `surfaces`. Every field mirrors the agent's JSON; the
 * surfaces only render these, they never derive geometry of their own.
 */

/** One `ar` frame: projected route, home and motion items per served camera. */
@Serializable
data class ArFrame(
    val v: Int = 1,
    @SerialName("ts_ms") val tsMs: Long = 0,
    val approx: Boolean = false,
    val reason: String? = null,
    val cameras: Map<String, ArCamera> = emptyMap(),
    val mission: MissionState? = null,
)

@Serializable
data class ArCamera(
    @SerialName("frame_w") val frameW: Float = 0f,
    @SerialName("frame_h") val frameH: Float = 0f,
    val reason: String? = null,
    val items: List<ArItem> = emptyList(),
)

/**
 * One AR item. The type is [t] (`wp`, `leg`, `home`, `fpv`, `fpath`, `fgoal`, `orbit`,
 * `edge`); [k] is the stable key interpolation matches across frames. Coordinates are
 * normalised to the frame, 0..1 with y down.
 */
@Serializable
data class ArItem(
    val t: String,
    val k: String? = null,
    val seq: Int? = null,
    val role: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    val gx: Float? = null,
    val gy: Float? = null,
    @SerialName("dist_m") val distM: Float? = null,
    @SerialName("eta_s") val etaS: Float? = null,
    val size: Float? = null,
    val pts: List<List<Float>> = emptyList(),
    val state: String? = null,
    val dir: String? = null,
    val cx: Float? = null,
    val cy: Float? = null,
    val of: String? = null,
    val ang: Float? = null,
) {
    /** The interpolation key: the agent's [k], or a type-and-role fallback for older agents. */
    val key: String get() = k ?: when (t) {
        "wp" -> "wp:$seq"
        "leg" -> "leg:$role:${seq ?: ""}"
        "edge" -> "edge:$of:${seq ?: ""}"
        else -> t
    }
}

/** The agent-computed mission state (`mission` in `state.v5`, copied into `ar` and `pilot`). */
@Serializable
data class MissionState(
    val state: String = "none",
    val seq: Int = 0,
    val total: Int = 0,
    @SerialName("item_reached") val itemReached: Int? = null,
    val id: Long? = null,
    val source: String? = null,
    @SerialName("age_ms") val ageMs: Long? = null,
    @SerialName("landing_seq") val landingSeq: Boolean = false,
)

/** One camera's 32×18 range grid: base64 log-coded cells plus a base64 measured bitmask. */
@Serializable
data class CameraGrid(
    val cols: Int,
    val rows: Int,
    val near: String,
    val measured: String,
    /** Depth only: `lidar` when this camera's cells are metric, `none` for relative bands. */
    @SerialName("scale_source") val scaleSource: String? = null,
)

/** One `obstacles` frame: 72 five-degree body sectors plus per-camera grids. */
@Serializable
data class ObstaclesFrame(
    val v: Int = 1,
    @SerialName("ts_ms") val tsMs: Long = 0,
    @SerialName("sectors_m") val sectorsM: List<Float?> = emptyList(),
    val cameras: Map<String, CameraGrid> = emptyMap(),
)

/** One `depth` frame: the same grid as [ObstaclesFrame], with the scale source of the fit. */
@Serializable
data class DepthFrame(
    val v: Int = 1,
    @SerialName("ts_ms") val tsMs: Long = 0,
    @SerialName("scale_source") val scaleSource: String = "none",
    val cameras: Map<String, CameraGrid> = emptyMap(),
)

/** One `surfaces` frame: landing-zone candidates on one camera. */
@Serializable
data class SurfacesFrame(
    val v: Int = 1,
    @SerialName("ts_ms") val tsMs: Long = 0,
    @SerialName("camera_id") val cameraId: String,
    val zones: List<SurfaceZone> = emptyList(),
)

@Serializable
data class SurfaceZone(
    val pts: List<List<Float>>,
    val score: Float,
    @SerialName("slope_deg") val slopeDeg: Float,
)

/** One decoded overlay frame from the navigation stream. */
sealed interface LayerFrame {
    data class Ar(val frame: ArFrame) : LayerFrame
    data class Obstacles(val frame: ObstaclesFrame) : LayerFrame
    data class Depth(val frame: DepthFrame) : LayerFrame
    data class Surfaces(val frame: SurfacesFrame) : LayerFrame
}
