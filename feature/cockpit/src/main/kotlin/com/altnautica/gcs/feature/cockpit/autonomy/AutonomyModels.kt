package com.altnautica.gcs.feature.cockpit.autonomy

/** A box in frame pixels (origin top-left of the camera frame). */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx: Float get() = x + w / 2
    val cy: Float get() = y + h / 2
    val bottom: Float get() = y + h
    val right: Float get() = x + w
}

/** Lock state the tracker reports for a detection. */
enum class LockState { Locked, Uncertain, Lost }

/** One detection of a batch. */
data class Detection(
    val box: Box,
    val classLabel: String,
    val confidence: Float,
    val trackId: Int?,
    val lockState: LockState?,
)

/** One detection batch for one camera, stamped with the local receive time. */
data class DetectionBatch(
    val cameraId: String,
    val frameId: Long,
    val frameWidth: Float,
    val frameHeight: Float,
    val receivedAtMs: Long,
    val items: List<Detection>,
)

/** Follow-me phases (`follow.state.phase`). */
enum class FollowPhase { Idle, Acquiring, Following, Coasting, Searching, Lost, Holding }

/** Follow-me's prediction of the target box in one camera (frame px). */
data class PredictedBox(val cameraId: String, val frameW: Float, val frameH: Float, val box: Box)

/** Range cue kinds follow-me fuses. */
enum class RangeCue { Ground, BoxHeight, Lidar }

/** The fields of `follow.state` the surfaces draw. */
data class FollowState(
    val phase: FollowPhase,
    val trackId: Int?,
    val confidence: Float?,
    val rangeM: Float?,
    val rangeSigmaM: Float?,
    val rangeCues: Set<RangeCue>,
    val targetSpeedMps: Float?,
    val aglM: Float?,
    /** `rng` or `baro`; null when unreported. */
    val aglSource: String?,
    /** `strict` or `smart`; null when unreported. */
    val reacquireMode: String?,
    val coastRemainingS: Float?,
    val pipelineLatencyMs: Float?,
    val holdReason: String?,
    val bearingDeg: Float?,
    val depressionDeg: Float?,
    /** Target north, east in the local NED frame. */
    val targetNed: Pair<Float, Float>?,
    val targetVelNed: Pair<Float, Float>?,
    val predicted: PredictedBox?,
)

/** A follow event from the navigation stream, stamped with the local receive time. */
sealed interface FollowEvent {
    val atMs: Long

    data class Reacquired(val trackId: Int, val similarity: Float, val gapS: Float, override val atMs: Long) : FollowEvent
    data class Lost(val trackId: Int?, val reason: String?, override val atMs: Long) : FollowEvent
}

/** Extnav health vision-nav reports. */
enum class ExtNavHealth { Good, Degraded, Lost }

/** The fields of vision-nav `nav.state` the surfaces draw. */
data class NavState(
    val mode: String?,
    val activeSet: Int?,
    val extnavHealth: ExtNavHealth?,
    val odomRateHz: Float?,
    val obstacleRateHz: Float?,
    val minObstacleM: Float?,
    val gpsFix: Int?,
    /** Age of the newest pose in ms (`pose_age_ms`), null while none. */
    val poseAgeMs: Long? = null,
)

/** One planar scan in the body FRD frame; angles clockwise from forward. 0 range = no return. */
data class ScanFrame(val angleStartRad: Float, val angleIncrementRad: Float, val rangesM: FloatArray, val receivedAtMs: Long) {
    override fun equals(other: Any?) = other is ScanFrame && other.receivedAtMs == receivedAtMs && rangesM.contentEquals(other.rangesM)
    override fun hashCode() = rangesM.contentHashCode() * 31 + receivedAtMs.hashCode()
}

/** SLAM pose health. */
enum class PoseHealth { Good, Degraded, Lost }

/** A SLAM pose in map_ned (north, east, metres) with the body yaw. */
data class PoseFrame(val northM: Float, val eastM: Float, val yawRad: Float, val health: PoseHealth, val receivedAtMs: Long)

/** One live occupancy tile: PNG bytes for cell block (ix, iy). */
data class OccupancyTile(val ix: Int, val iy: Int, val png: ByteArray) {
    override fun equals(other: Any?) = other is OccupancyTile && other.ix == ix && other.iy == iy && other.png.contentEquals(png)
    override fun hashCode() = (ix * 31 + iy) * 31 + png.contentHashCode()
}

/** A `/api/v1/map/live` reply. */
data class OccupancyDelta(val seq: Long, val resM: Float, val tileCells: Int, val tiles: List<OccupancyTile>)

/** A target designation held by the host: a track, or a box on a camera. */
data class Designation(val cameraId: String, val trackId: Int?, val box: Box?)
