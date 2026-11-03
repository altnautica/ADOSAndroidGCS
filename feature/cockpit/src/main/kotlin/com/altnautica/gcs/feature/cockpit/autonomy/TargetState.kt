package com.altnautica.gcs.feature.cockpit.autonomy

/** HUD v2 target states, highest draw priority first within their group. */
enum class TargetKind { Candidate, Tracked, Locked, Acquiring, Following, Holding, Coasting, Searching, SearchingOffFrame, Lost }

/** State modifiers. */
enum class TargetModifier { Uncertain, FollowStale, Reacquired }

/** One tag on a target. The surface formats it from string resources. */
sealed interface TargetTag {
    data class Candidate(val cls: String, val pct: Int) : TargetTag
    data class Tracked(val cls: String, val id: Int, val uncertain: Boolean) : TargetTag
    data class Lock(val id: Int, val pct: Int, val uncertain: Boolean) : TargetTag
    data class Acquiring(val id: Int, val uncertain: Boolean) : TargetTag
    data class Track(val pct: Int, val uncertain: Boolean) : TargetTag

    /** [reason] is already shortened (`NO RANGE`); null reads `HOLD`. */
    data class Hold(val reason: String?) : TargetTag
    data class Coast(val seconds: Float) : TargetTag
    data object Searching : TargetTag
    data class SearchingBearing(val deg: Int) : TargetTag
    data object Lost : TargetTag
    data object FollowStale : TargetTag
    data class Range(val m: Float) : TargetTag
    data class Sigma(val twoSigmaM: Float) : TargetTag
    data class Estimate(val m: Float) : TargetTag
}

/** One thing to draw. [rect] is in frame px; null for the off-frame arrow. */
data class DrawItem(
    val kind: TargetKind,
    val rect: Box?,
    val top: TargetTag?,
    val bottom: List<TargetTag>,
    val modifiers: Set<TargetModifier>,
    val trackId: Int?,
    val confidence: Float,
    /** Off-frame arrow: bearing relative to the camera axis, degrees clockwise. */
    val relBearingDeg: Float? = null,
    /** Coasting ghost path in frame px, oldest first, ending at the predicted feet point. */
    val ghost: List<Pair<Float, Float>> = emptyList(),
    /** The detection behind this item, for designation. */
    val detection: Detection? = null,
)

/** Surface-held inputs the resolver cannot derive from one batch. */
data class ResolveOptions(
    val minCandidateConfidence: Float = 0.45f,
    val videoLost: Boolean = false,
    val vehicleYawDeg: Float? = null,
    val cameraYawOffsetDeg: Float = 0f,
    val cameraIsDown: Boolean = false,
    /** Last drawn rect of the followed track, for `LOST`. */
    val lastFollowedRect: Box? = null,
    /** When the follow went lost (phase change or `follow.lost`), on the surface clock. */
    val lostAtMs: Long? = null,
    /** Followed feet points over the second before the coast began, oldest first. */
    val ghost: List<Pair<Float, Float>> = emptyList(),
    /** Camera horizontal and vertical field of view in degrees, when the leg reports them. */
    val cameraHfovDeg: Float? = null,
    val cameraVfovDeg: Float? = null,
    /** Camera mount pitch in degrees (negative looks down). */
    val cameraPitchDeg: Float = 0f,
)

object TargetState {
    const val DETECTION_STALE_MS = 2_000L
    const val FOLLOW_STALE_MS = 2_000L
    const val LOST_VISIBLE_MS = 900L
    const val REACQUIRED_SNAP_MS = 320L
    const val MAX_ITEMS = 32
    const val MAX_CANDIDATE_TAGS = 8

    /** HUD v2 §4: every item to draw on [cameraId]'s video. */
    fun resolveTargets(
        batch: DetectionBatch?,
        cameraId: String,
        designated: Designation?,
        follow: FollowState?,
        followAgeMs: Long,
        events: List<FollowEvent>,
        nowMs: Long,
        opts: ResolveOptions = ResolveOptions(),
    ): List<DrawItem> {
        if (opts.videoLost) return emptyList()
        val fresh = follow != null && followAgeMs <= FOLLOW_STALE_MS
        val stale = follow != null && !fresh
        val out = ArrayList<DrawItem>()
        val live = batch?.takeIf { it.cameraId == cameraId && nowMs - it.receivedAtMs <= DETECTION_STALE_MS }
        val reacquired = events.filterIsInstance<FollowEvent.Reacquired>().lastOrNull { nowMs - it.atMs in 0..REACQUIRED_SNAP_MS }
        for (d in live?.items.orEmpty()) {
            val mods = HashSet<TargetModifier>()
            val uncertain = d.lockState == LockState.Uncertain
            if (reacquired != null && d.trackId == reacquired.trackId) mods += TargetModifier.Reacquired
            val pct = pct(d.confidence)
            if (isDesignated(d, cameraId, designated)) {
                if (uncertain) mods += TargetModifier.Uncertain
                val id = d.trackId ?: 0
                val phase = follow?.phase?.takeIf { fresh && follow.trackId == d.trackId }
                val rangeTags = rangeTags(follow?.takeIf { fresh })
                val item = when (phase) {
                    FollowPhase.Acquiring -> DrawItem(TargetKind.Acquiring, d.box, TargetTag.Acquiring(id, uncertain), rangeTags, mods, d.trackId, d.confidence, detection = d)
                    FollowPhase.Following -> DrawItem(TargetKind.Following, d.box, TargetTag.Track(pct, uncertain), rangeTags, mods, d.trackId, d.confidence, detection = d)
                    FollowPhase.Holding -> DrawItem(TargetKind.Holding, d.box, TargetTag.Hold(holdReason(follow?.holdReason)), rangeTags, mods, d.trackId, d.confidence, detection = d)
                    else -> {
                        if (stale) mods += TargetModifier.FollowStale
                        val bottom = if (stale) listOf(TargetTag.FollowStale) else rangeTags.take(1)
                        DrawItem(TargetKind.Locked, d.box, TargetTag.Lock(id, pct, uncertain), bottom, mods, d.trackId, d.confidence, detection = d)
                    }
                }
                out += item
            } else if (d.trackId != null) {
                if (d.lockState == LockState.Lost) continue
                if (uncertain) mods += TargetModifier.Uncertain
                out += DrawItem(TargetKind.Tracked, d.box, TargetTag.Tracked(classWord(d.classLabel), d.trackId, uncertain), emptyList(), mods, d.trackId, d.confidence, detection = d)
            } else if (d.confidence >= opts.minCandidateConfidence) {
                out += DrawItem(TargetKind.Candidate, d.box, TargetTag.Candidate(classWord(d.classLabel), pct), emptyList(), mods, null, d.confidence, detection = d)
            }
        }
        follow?.takeIf { fresh }?.let { f -> phaseItem(f, cameraId, opts)?.let(out::add) }
        val lostEventAt = events.lastOrNull { it is FollowEvent.Lost }?.atMs
        val lostPhaseAt = opts.lostAtMs?.takeIf { fresh && follow?.phase == FollowPhase.Lost }
        val lostStart = listOfNotNull(lostEventAt, lostPhaseAt).maxOrNull()
        if (lostStart != null && nowMs - lostStart in 0..LOST_VISIBLE_MS && opts.lastFollowedRect != null) {
            out += DrawItem(TargetKind.Lost, opts.lastFollowedRect, TargetTag.Lost, emptyList(), emptySet(), follow?.trackId, 0f)
        }
        val ranked = out.sortedWith(compareBy<DrawItem> { priority(it.kind) }.thenByDescending { it.confidence }).take(MAX_ITEMS)
        var candidateTags = 0
        return ranked.map { item ->
            if (item.kind == TargetKind.Candidate && ++candidateTags > MAX_CANDIDATE_TAGS) item.copy(top = null) else item
        }
    }

    private fun phaseItem(follow: FollowState, cameraId: String, opts: ResolveOptions): DrawItem? {
        val p = follow.predicted?.takeIf { it.cameraId == cameraId }
        return when (follow.phase) {
            FollowPhase.Coasting -> p?.let {
                val est = follow.rangeM?.let { r -> listOf(TargetTag.Estimate(r)) }.orEmpty()
                DrawItem(
                    TargetKind.Coasting, it.box, TargetTag.Coast(follow.coastRemainingS ?: 0f), est, emptySet(), follow.trackId, 1f,
                    ghost = opts.ghost + (it.box.cx to it.box.bottom),
                )
            }
            FollowPhase.Searching -> when {
                p != null -> DrawItem(TargetKind.Searching, p.box, TargetTag.Searching, emptyList(), emptySet(), follow.trackId, 1f)
                follow.predicted == null -> offFrame(follow, opts, requireOutside = false)
                else -> null
            }
            // A followed target whose bearing or depression leaves the camera's field of view.
            FollowPhase.Following -> if (p == null) offFrame(follow, opts, requireOutside = true) else null
            else -> null
        }
    }

    /**
     * The edge arrow toward the target. With [requireOutside] it is drawn only when the
     * target lies outside the camera's field of view: relative bearing beyond half the
     * horizontal FOV, or depression beyond half the vertical FOV about the mount pitch.
     */
    private fun offFrame(follow: FollowState, opts: ResolveOptions, requireOutside: Boolean): DrawItem? {
        if (opts.cameraIsDown) return null
        val bearing = follow.bearingDeg ?: return null
        val yaw = opts.vehicleYawDeg ?: return null
        val rel = wrap180(bearing - (yaw + opts.cameraYawOffsetDeg))
        if (requireOutside && !outsideFov(rel, follow.depressionDeg, opts)) return null
        return DrawItem(
            TargetKind.SearchingOffFrame, null, TargetTag.SearchingBearing(Math.floorMod(Math.round(bearing), 360)),
            emptyList(), emptySet(), follow.trackId, 1f, relBearingDeg = rel,
        )
    }

    /** True when ([relDeg], [depressionDeg]) falls outside the camera's FOV; false when the FOV is unknown. */
    fun outsideFov(relDeg: Float, depressionDeg: Float?, opts: ResolveOptions): Boolean {
        val hfov = opts.cameraHfovDeg ?: return false
        if (kotlin.math.abs(relDeg) > hfov / 2) return true
        val vfov = opts.cameraVfovDeg ?: return false
        val dep = depressionDeg ?: return false
        // Elevation above the camera axis: a mount pitched down (negative) sees lower targets.
        return kotlin.math.abs(-dep - opts.cameraPitchDeg) > vfov / 2
    }

    private fun isDesignated(d: Detection, cameraId: String, des: Designation?): Boolean {
        if (des == null || des.cameraId != cameraId) return false
        return if (des.trackId != null) d.trackId == des.trackId else d.trackId == null && des.box == d.box
    }

    private fun rangeTags(follow: FollowState?): List<TargetTag> {
        val r = follow?.rangeM ?: return emptyList()
        return listOfNotNull(TargetTag.Range(r), follow.rangeSigmaM?.let { TargetTag.Sigma(2 * it) })
    }

    fun priority(kind: TargetKind): Int = when (kind) {
        TargetKind.Following, TargetKind.Acquiring, TargetKind.Holding -> 0
        TargetKind.Locked -> 1
        TargetKind.Coasting, TargetKind.Searching, TargetKind.SearchingOffFrame, TargetKind.Lost -> 2
        TargetKind.Tracked -> 3
        TargetKind.Candidate -> 4
    }

    fun pct(confidence: Float): Int = Math.round(confidence * 100)

    /** Class word: uppercased, anything outside `[A-Z0-9 ]` becomes a space, at most 12 characters. */
    fun classWord(label: String): String =
        label.uppercase().map { if (it in 'A'..'Z' || it in '0'..'9' || it == ' ') it else ' ' }.joinToString("").take(12)

    /** `HOLD_NO_RANGE` → `NO RANGE`; null stays null. */
    fun holdReason(reason: String?): String? = reason?.removePrefix("HOLD_")?.replace('_', ' ')

    fun wrap180(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }
}
