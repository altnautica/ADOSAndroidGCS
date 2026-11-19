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

/** One detection of a batch. [held]: kept from the camera's last whole-frame batch, outside a crop-only batch's window. */
data class Detection(
    val box: Box,
    val classLabel: String,
    val confidence: Float,
    val trackId: Int?,
    val lockState: LockState?,
    val held: Boolean = false,
)

/**
 * One detection batch for one camera, stamped with the local receive time. [tsMs] is the agent's
 * frame time. [cropWindow] is set only on a crop-only batch (frame pixels); [wholeFrame] is the
 * camera's latest whole-frame batch, kept for those ([CropHold]).
 */
data class DetectionBatch(
    val cameraId: String,
    val frameId: Long,
    val frameWidth: Float,
    val frameHeight: Float,
    val receivedAtMs: Long,
    val items: List<Detection>,
    val tsMs: Long = 0L,
    val cropWindow: Box? = null,
    val wholeFrame: DetectionBatch? = null,
)

/**
 * Follow-me phases (`follow.state.phase`), [wire] as the agent sends them. Once the lock is
 * lost the follow is [Predicting] (flying toward the predicted standoff), [Repositioning]
 * (flying to a vantage point), [Searching] (sweeping from the vantage point, widening in
 * steps), then [HoldingAwaitOperator] (stopped at a latched hover point, waiting for the
 * operator).
 */
enum class FollowPhase(val wire: String) {
    Idle("idle"),
    Acquiring("acquiring"),
    Following("following"),
    Holding("holding"),
    Predicting("predicting"),
    Repositioning("repositioning"),
    Searching("searching"),
    HoldingAwaitOperator("holding-await-operator"),
    ;

    /** The lock is lost and the subject is being sought. */
    val recovering: Boolean get() = this == Predicting || this == Repositioning || this == Searching

    /** The spec key of the phase word (`follow.phase.<wire>`). */
    val wordKey: String get() = "follow.phase.$wire"

    companion object {
        /**
         * The phase [wire] names. Follow-me 0.4.9 and older report `coasting` (flying on the
         * prediction of a lost lock) and `lost` (holding): they read as [Predicting] and
         * [Holding], never as a follow, and never as the hold that waits for the operator.
         */
        fun of(wire: String?): FollowPhase? = entries.firstOrNull { it.wire == wire } ?: when (wire) {
            "coasting" -> Predicting
            "lost" -> Holding
            else -> null
        }
    }
}

/** The search ellipse around the predicted subject position (map frame); [headingDeg] is the major axis as a bearing. */
data class SearchArea(val centerNe: Pair<Float, Float>, val semiMajorM: Float, val semiMinorM: Float, val headingDeg: Float)

/** What hides the subject from the vantage point: `narrow` (a trunk, a column) or `wide` (a wall, a corner). */
data class Occluder(val ne: Pair<Float, Float>, val kind: String)

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
    /** Seconds left of the recovery's search budget. */
    val reacquireRemainingS: Float?,
    val pipelineLatencyMs: Float?,
    val holdReason: String?,
    val bearingDeg: Float?,
    val depressionDeg: Float?,
    /** Target north, east in the local NED frame. */
    val targetNed: Pair<Float, Float>?,
    val targetVelNed: Pair<Float, Float>?,
    val predicted: PredictedBox?,
    val searchBudgetS: Float? = null,
    /** 0-based widening step while searching, else null. */
    val searchStep: Int? = null,
    val searchArea: SearchArea? = null,
    /** The predicted subject path while recovering (north, east). */
    val predictedPathNe: List<Pair<Float, Float>> = emptyList(),
    val vantageNe: Pair<Float, Float>? = null,
    /** `enroute`, `arrived` or `no-vantage`; null when unreported. */
    val vantageState: String? = null,
    val occluder: Occluder? = null,
    /** The latched hover point while holding for the operator. */
    val holdNe: Pair<Float, Float>? = null,
    /** `follow` or `hold`; null when unreported. */
    val uncertainMode: String? = null,
    val predictiveSearch: Boolean? = null,
    /** A surface writes `operatorResumeSeq + 1` to resume searching. */
    val operatorResumeSeq: Int = 0,
    /** The subject has been faster than [speedLimitMps] for a while. */
    val outpaced: Boolean = false,
    val speedLimitMps: Float? = null,
)

/** A follow event from the navigation stream, stamped with the local receive time. */
sealed interface FollowEvent {
    val atMs: Long

    /** [phase] is the phase the re-lock happened in; [mahalanobis] the position gate distance in sigmas. */
    data class Reacquired(
        val trackId: Int,
        val similarity: Float,
        val gapS: Float,
        override val atMs: Long,
        val mahalanobis: Float? = null,
        val phase: FollowPhase? = null,
    ) : FollowEvent

    /** [awaitingOperator]: the follow holds at [holdNe] for the operator and keeps the designation. */
    data class Lost(
        val trackId: Int?,
        val reason: String?,
        override val atMs: Long,
        val awaitingOperator: Boolean = false,
        val holdNe: Pair<Float, Float>? = null,
    ) : FollowEvent

    /** Follow-me stopped (its lease revoked, or the operator stopped it). */
    data class Stopped(val reason: String, override val atMs: Long) : FollowEvent
}

/** Extnav health Navigation reports. */
enum class ExtNavHealth { Good, Degraded, Lost }

/** The fields of Navigation `nav.state` the surfaces draw. */
data class NavState(
    val mode: String?,
    val activeSet: Int?,
    val extnavHealth: ExtNavHealth?,
    val odomRateHz: Float?,
    val obstacleRateHz: Float?,
    val minObstacleM: Float?,
    val gpsFix: Int?,
    /** Why the pose is degraded or lost (`health_reason`: `corridor_unmeasured`, `flow_disagrees`, ...); null when none is named. */
    val healthReason: String? = null,
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
