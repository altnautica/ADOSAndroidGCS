package com.altnautica.gcs.core.model

import kotlinx.coroutines.flow.StateFlow

/**
 * Who commands the vehicle's motion, as the onboard pilot reports it in the `pilot`
 * navigation-stream kind. [Unknown] covers a value this build does not know.
 */
enum class PilotOwner(val wire: String) {
    Idle("idle"),
    Pilot("pilot"),
    Skill("skill"),
    Goto("goto"),
    Swarm("swarm"),
    MissionFc("mission_fc"),
    FcMode("fc_mode"),
    Tuning("tuning"),
    Unknown("");

    companion object {
        fun of(wire: String?): PilotOwner = entries.firstOrNull { it.wire.isNotEmpty() && it.wire == wire } ?: Unknown
    }
}

/** Where operator sticks go: the onboard pilot, the flight controller directly, or the ELRS link. */
enum class ControlMode(val wire: String) {
    Ados("ados"),
    FcManual("fc_manual"),
    Elrs("elrs");

    companion object {
        fun of(wire: String?): ControlMode? = entries.firstOrNull { it.wire == wire }
    }
}

/** Stick shaping profile of the onboard pilot. */
enum class PilotProfile(val wire: String) {
    Cine("cine"),
    Normal("normal"),
    Sport("sport");

    companion object {
        fun of(wire: String?): PilotProfile? = entries.firstOrNull { it.wire == wire }
    }
}

/** Obstacle avoidance setting. */
enum class AvoidMode(val wire: String) {
    Brake("brake"),
    Bypass("bypass"),
    Off("off");

    companion object {
        fun of(wire: String?): AvoidMode? = entries.firstOrNull { it.wire == wire }
    }
}

/** What the planner is doing about obstacles right now. */
enum class AvoidState(val wire: String) {
    Clear("clear"),
    Detour("detour"),
    Blocked("blocked"),
    Off("off");

    companion object {
        fun of(wire: String?): AvoidState? = entries.firstOrNull { it.wire == wire }
    }
}

/** Airframe model the onboard pilot flies. */
enum class PilotVehicleClass(val wire: String) {
    Multirotor("multirotor"),
    FixedWing("fixedwing"),
    Vtol("vtol");

    companion object {
        fun of(wire: String?): PilotVehicleClass? = entries.firstOrNull { it.wire == wire }
    }
}

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
)

enum class ReadinessSeverity { Block, Warn }

/** One pilot readiness item; a [ReadinessSeverity.Block] that is not [ok] refuses arming in pilot control. */
data class ReadinessItem(val id: String, val ok: Boolean, val severity: ReadinessSeverity, val msg: String)

/** The skill holding the motion lease. */
data class PilotSkillRef(val pluginId: String, val skillId: String, val label: String)

/** The surface whose sticks the pilot currently accepts. */
data class InputSession(val src: String, val label: String)

/** The centred-sticks handover gate: pending until the incoming sticks rest centred. */
data class HandoverGate(val pending: Boolean, val progress: Float)

/** Steps of the launch sequence. */
enum class LaunchStep(val wire: String) {
    Checks("checks"),
    Arm("arm"),
    Takeoff("takeoff"),
    After("after"),
    Done("done");

    companion object {
        fun of(wire: String?): LaunchStep? = entries.firstOrNull { it.wire == wire }
    }
}

/** A launch sequence in progress: its [step], the after-takeoff action, and an error once refused. */
data class LaunchSequence(val step: LaunchStep, val after: String?, val error: String?)

/** A transient full-width announcement; [textKey] names a cockpit-spec string, [untilMs] is vehicle time. */
data class PilotBanner(val textKey: String, val args: Map<String, String>, val untilMs: Long?)

/**
 * The onboard pilot's state (`pilot` navigation kind). Fields the vehicle leaves out are
 * null or empty. [receivedAtMs] is the local monotonic receive time, used for freshness.
 */
data class PilotState(
    val owner: PilotOwner,
    val ownerLabel: String?,
    val skill: PilotSkillRef?,
    val controlMode: ControlMode?,
    val profile: PilotProfile?,
    val avoid: AvoidMode?,
    val fcMode: Int?,
    val fcModeName: String?,
    /** Null while the pilot has no flight-controller link: the arm state is unknown. */
    val armed: Boolean?,
    /** Null while the pilot has no flight-controller link: on the ground or not is unknown. */
    val landed: Boolean?,
    val inputSrc: String?,
    val inputAgeMs: Long?,
    val holdReason: String?,
    val avoidState: AvoidState?,
    val minClearanceM: Float?,
    val goalNed: List<Float>?,
    val planNed: List<Pair<Float, Float>>,
    val setpointNed: List<Float>?,
    val readiness: List<ReadinessItem>,
    val linkLossInS: Float?,
    val mission: MissionStatus?,
    val vehicleClass: PilotVehicleClass?,
    val inputSession: InputSession?,
    val gate: HandoverGate?,
    val sequence: LaunchSequence?,
    val banner: PilotBanner?,
    val rthCountdownS: Float?,
    val receivedAtMs: Long,
    /** Pilot operations the agent accepts from this surface (`goto`, ...). */
    val surfaceOps: Set<String> = emptySet(),
    /** Height above ground and its source (`rangefinder`, `baro`, ...), when the agent knows it. */
    val aglM: Float? = null,
    val aglSource: String? = null,
    /** Recent FC status texts the agent relays, newest last. */
    val statusTexts: List<PilotStatusText> = emptyList(),
    /** What the pilot does once the control link times out (`rth`, `rtl`, `hold`, `loiter`, `land`). */
    val linkLossAction: String? = null,
    /** True while a VTOL airframe transitions between hover and forward flight. */
    val vtolTransitioning: Boolean = false,
    /** The setup step a tuning flight is running (`autotune_roll`, ...), while one runs. */
    val setupStep: String? = null,
    /** True during a true FC failsafe (RC loss, battery, GCS link); the agent omits it while false. */
    val fcFailsafe: Boolean = false,
    /** What raised the failsafe (`rc_or_failsafe`, `fc`), while [fcFailsafe] holds. */
    val failsafeCause: String? = null,
) {
    /** The first unmet blocking readiness item, or null when nothing blocks. */
    val firstBlock: ReadinessItem? get() = readiness.firstOrNull { !it.ok && it.severity == ReadinessSeverity.Block }

    /** True while the arm or landed state is unknown (no FC link): the drone may be airborne. */
    val armStateUnknown: Boolean get() = armed == null || landed == null

    /** Not known to be disarmed or on the ground: armed and flying, or an unknown arm state. */
    val possiblyAirborne: Boolean get() = armed != false && landed != true

    /** Known to be armed and off the ground. */
    val knownAirborne: Boolean get() = armed == true && landed == false

    companion object {
        /** The chip dims and reads LINK when the pilot state is older than this. */
        const val STALE_MS = 2_000L
    }
}

/** One FC status text relayed by the pilot: MAVLink [severity] (0 emergency … 7 debug), [ageMs] since the FC sent it. */
data class PilotStatusText(val severity: Int, val text: String, val ageMs: Long?)

/**
 * One stick frame for the onboard pilot. Axes are in [-1, 1] after the surface's mode
 * mapping and deadzone: roll + right, pitch + forward, throttle + climb, yaw + clockwise.
 * [buttons] is the controller button bitfield (W3C standard index → bit).
 */
data class PilotInput(val roll: Float, val pitch: Float, val throttle: Float, val yaw: Float, val buttons: Long)

/** A named pilot action with its optional arguments (`launch`, `hand_to_fc`). */
data class PilotAction(val name: String, val args: Map<String, Any?> = emptyMap()) {
    companion object {
        const val PAUSE = "pause"
        const val RESUME = "resume"
        const val TAKE_CONTROL = "take_control"
        const val HAND_TO_FC = "hand_to_fc"
        const val LAUNCH = "launch"
        const val TAKEOFF = "takeoff"
        const val LAND = "land"
        const val RTH = "rth"
        const val ARM = "arm"
        const val DISARM = "disarm"
        const val KILL = "kill"

        /** Cancels a low-battery return countdown without braking (pause also cancels it and brakes). */
        const val RTH_CANCEL = "rth_cancel"

        /** Flies to a map point: `{lat, lon, alt_m}`, altitude above home. Offered when `surface_ops` lists it. */
        const val GOTO = "goto"

        fun profile(p: PilotProfile) = PilotAction("profile:${p.wire}")
        fun controlMode(m: ControlMode) = PilotAction("control_mode:${m.wire}")
        fun avoid(m: AvoidMode) = PilotAction("avoid:${m.wire}")

        /** VTOL transition: to forward flight when [toFixedWing], else to hover. */
        fun vtol(toFixedWing: Boolean) = PilotAction(if (toFixedWing) "vtol:fw" else "vtol:mc")
    }
}

/** The pilot's answer to one action sent on the [PilotLink]. */
sealed interface PilotActionResult {
    data object Accepted : PilotActionResult

    /** The pilot refused the action; [reason] is its `err` text when it gave one. */
    data class Refused(val reason: String?) : PilotActionResult

    /** Sent, but no `action_result` arrived within the answer window. */
    data object NoAnswer : PilotActionResult

    /** Not sent: the channel is down. */
    data object Unavailable : PilotActionResult
}

/**
 * The live pilot channel of one vehicle (`/api/v1/pilot/stream`). Input frames are
 * dropped while [connected] is false; an action waits for the pilot's `action_result`.
 */
interface PilotLink {
    /** Name this surface reports as its input source. */
    val src: String

    val connected: StateFlow<Boolean>

    /** Latest pilot state pushed on this link, or null before the first. */
    val state: StateFlow<PilotState?>

    /**
     * True while a pilot state arrived within [PilotState.STALE_MS]. Channel keepalives
     * keep [connected] up but say nothing about whether the pilot itself answers.
     */
    val stateFresh: Boolean

    fun input(frame: PilotInput)

    /** Ends this surface's stick session; the pilot brakes to a hold. */
    fun release()

    /** Sends [action] and suspends until the pilot answers it or the answer window ends. */
    suspend fun action(action: PilotAction): PilotActionResult

    /** Takes the stick session over from another surface. */
    fun takeSession(): Boolean
}
