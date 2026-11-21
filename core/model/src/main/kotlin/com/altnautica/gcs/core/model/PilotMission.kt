package com.altnautica.gcs.core.model

/** Mission execution state, computed once on the vehicle from the flight controller's reports. */
enum class MissionState(val wire: String) {
    None("none"),
    Loaded("loaded"),
    Active("active"),
    Paused("paused"),
    Complete("complete"),
    Returning("returning");

    companion object {
        fun of(wire: String?): MissionState? = entries.firstOrNull { it.wire == wire }
    }
}

/** The vehicle's mission status. [seq]/[total] follow MISSION_CURRENT; [id] is 0 or null when unsupported. */
data class MissionStatus(
    val state: MissionState,
    val seq: Int,
    val total: Int,
    val itemReached: Int?,
    val id: Long?,
    val fromMissionState: Boolean,
    val ageMs: Long?,
    val landingSeq: Boolean,
    val uploadsSeen: Long?,
    /** The item the vehicle flies towards, when the agent reports it. */
    val target: MissionTarget? = null,
    /** Waypoints skipped this flight (coverage gaps), oldest first. */
    val gaps: List<MissionGap> = emptyList(),
)

/** The active mission item: its [seq], MAV_CMD [cmd], local-NED position [ned] (m) and acceptance radius. */
data class MissionTarget(val seq: Int, val cmd: Int?, val ned: List<Float>?, val radiusM: Float?)

/**
 * A skipped waypoint: [reason] is `stall_timeout` (the countdown ran out), `stall_operator`, or,
 * skipped at once as proven unreachable, `unreachable_inside_obstacle` (the waypoint lies inside
 * a seen obstacle) or `unreachable_no_path` (no route to it within 200 m); [atMs] epoch ms.
 */
data class MissionGap(val seq: Int, val reason: String, val atMs: Long?)

/**
 * A detour round a blocked mission leg (`mission_detour`): the flight controller flies GUIDED
 * while the pilot routes to [targetNed] (local NED, m), the leg's next free point, then resumes
 * the mission at item [seq]. [sinceMs] epoch ms.
 */
data class MissionDetour(val seq: Int, val targetNed: List<Float>, val sinceMs: Long?)

/**
 * A return home the pilot routes itself (`return_route`): the flight controller's own return
 * was blocked, so the pilot flies [remainingM] (m, the planned way still to fly) against
 * [straightM] (m, the straight line home). [sinceMs] epoch ms.
 */
data class ReturnRoute(val remainingM: Float, val straightM: Float, val sinceMs: Long?)

/** What stalled: a mission leg or the return home. */
enum class MissionStallKind(val wire: String) {
    Mission("mission"),
    Return("return");

    companion object {
        fun of(wire: String?): MissionStallKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * No progress towards the active waypoint (or home): the vehicle holds for the operator.
 * [skipInS] counts down to the automatic action (skip for a mission, land for a return);
 * null holds until the operator decides. [windowS] is the no-progress window that tripped it.
 * [returnInS] counts down to a SmartRTL home on its own: a mission held for the operator in a
 * confined place (no skip left, [skipInS] null) that no operator has answered; null otherwise.
 */
data class MissionStall(
    val kind: MissionStallKind,
    val seq: Int?,
    val wpLabel: String?,
    val sinceMs: Long?,
    val skipInS: Float?,
    val windowS: Float,
    val returnInS: Float? = null,
)
