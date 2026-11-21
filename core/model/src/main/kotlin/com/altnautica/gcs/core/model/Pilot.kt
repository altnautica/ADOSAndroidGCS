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

/**
 * What the planner is doing about obstacles right now. [Stale]: avoidance is on and the
 * lidar stopped reporting, or its scans carry no return where open air is not believable (a
 * confined place, or after a surface was seen this flight), so the pilot holds and refuses
 * motion until scans it can trust arrive.
 * [Unavailable]: avoidance is on but the drone has no lidar; sticks fly, Fly Here is refused.
 */
enum class AvoidState(val wire: String) {
    Clear("clear"),
    Detour("detour"),
    Blocked("blocked"),
    Stale("stale"),
    Unavailable("unavailable"),
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

/** The kind of place the operator flies in (`place_class`): never chosen automatically. */
enum class PlaceClass(val wire: String) {
    /** Open sites: the default obstacle margin. */
    Open("open"),

    /** Mines, tunnels and rooms: a margin from the prop tips and a slow cap near the limit. */
    Confined("confined");

    companion object {
        fun of(wire: String?): PlaceClass? = entries.firstOrNull { it.wire == wire }
    }
}

/** Whether the vehicle flies with an RC transmitter, as the operator declared it in setup (`rc_receiver`). */
enum class RcReceiver(val wire: String) {
    Present("present"),

    /** No RC transmitter: the app is the only control, and the pilot watches each app instance's link on its own. */
    Absent("absent");

    companion object {
        fun of(wire: String?): RcReceiver? = entries.firstOrNull { it.wire == wire }
    }
}

/** How the loaded mission's legs compare with the clearance the confined profile needs. */
enum class RouteCheckState(val wire: String) {
    /** Every sampled point is observed and clear by at least the required distance. */
    Feasible("feasible"),

    /** Some legs run through cells not observed yet: allowed, flown slowly. */
    Unverified("unverified"),

    /** An observed point is closer to an obstacle than the required distance. */
    Infeasible("infeasible");

    companion object {
        fun of(wire: String?): RouteCheckState? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The tightest checked point of the route: on the leg [fromSeq]→[toSeq], at [ned] (local
 * north, east, down in metres), [clearanceM] from the nearest obstacle against [requiredM].
 * Distances keep the wire's double precision, so they round as the agent and the web print them.
 */
data class RoutePinch(val fromSeq: Int, val toSeq: Int, val ned: List<Double>, val clearanceM: Double, val requiredM: Double)

/**
 * The confined profile's check of the loaded mission ([PilotState.routeCheck]): its [state],
 * the clearance it needs ([requiredM] = [marginM] plus the pose uncertainty term), the live
 * pose σ ([sigmaM], null when unknown), the [pinch] and the legs ([unverifiedLegs], by their
 * end waypoint) running through unobserved cells. [unchecked] lists the mission items the check
 * cannot place (no position; the legs to and from them are not checked) and [truncated] says the
 * mission is longer than the route the check holds; [stale] says the mission feed may not hold the
 * FC's mission (not downloaded yet, or out of step with its count). Each keeps the state at best
 * unverified.
 */
data class RouteCheck(
    val state: RouteCheckState,
    val requiredM: Double,
    val marginM: Double?,
    val sigmaM: Double?,
    val pinch: RoutePinch?,
    val unverifiedLegs: List<Int>,
    val unchecked: List<Int> = emptyList(),
    val truncated: Boolean = false,
    val stale: Boolean = false,
)

enum class ReadinessSeverity { Block, Warn }

/** One pilot readiness item; a [ReadinessSeverity.Block] that is not [ok] refuses arming in pilot control. */
data class ReadinessItem(val id: String, val ok: Boolean, val severity: ReadinessSeverity, val msg: String)

/** The skill holding the motion lease. */
data class PilotSkillRef(val pluginId: String, val skillId: String, val label: String)

/** The surface whose sticks the pilot currently accepts. */
data class InputSession(val src: String, val label: String, val session: Long? = null)

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

/**
 * Phases of the supervised lift-off: spool, level a learned trim, find the thrust a side unloads at, bracket the
 * trim on the skids, settle, commit off the ground, climb, then done or stopped.
 */
enum class LiftoffPhase(val wire: String) {
    Spool("spool"),
    Level("level"),
    Find("find"),
    Bracket("bracket"),
    Settle("settle"),
    Commit("commit"),
    Climb("climb"),
    Done("done"),
    Stopped("stopped");

    companion object {
        fun of(wire: String?): LiftoffPhase? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The steady take-off of a launch: its [phase], the status line ([text], e.g. "Spinning up",
 * "Take-off stopped"), the longer [detail] line where a surface has room ("Climbing to 3 m",
 * "The drone started to tip. Motors off."), the trim source (`learned` or `probe`) and the tilt in degrees.
 */
data class Liftoff(val phase: LiftoffPhase, val text: String?, val trim: String?, val tiltDeg: Float?, val detail: String? = null)

/** A launch sequence in progress: its [step], the after-takeoff action, an error once refused, and the lift-off while it runs. */
data class LaunchSequence(val step: LaunchStep, val after: String?, val error: String?, val liftoff: Liftoff? = null)

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
    /**
     * Why the setup wizard's air run aborted, as a setup-strings key
     * (`setup.blocker.aborted_<reason>`), or null.
     */
    val setupAbort: String? = null,
    /**
     * The pilot's own low-battery action, `land` (it may fly without GNSS) or `rth`, while its
     * countdown runs ([rthCountdownS]) and after it fired; null while none.
     */
    val lowBatteryAction: String? = null,
    /** The charge a low-battery return home needs, published airborne; null on the ground. */
    val batteryReserve: BatteryReserve? = null,
    /** The horizontal speed cap the pilot applies to every setpoint it flies (`gnss-denied`, `confined-near`), or null. */
    val speedCap: SpeedCap? = null,
    /** The pilot does not trust the horizontal position (an older agent sends none), or null. */
    val positionUnverified: PositionUnverified? = null,
    /** Leaves or a ceiling overhead along the lease goal's way (`overhead_ahead`), or null while nothing lies ahead. */
    val overheadAhead: OverheadAhead? = null,
    /** What the pilot sees obstacles with and the speed that stops inside its seen-clear range (`sensing`), or null (an older agent). */
    val sensing: Sensing? = null,
    /** No progress on the mission or the return: present while the vehicle holds for the operator. */
    val missionStall: MissionStall? = null,
    /** The place class the operator chose; a pilot that does not report one flies the open profile. */
    val placeClass: PlaceClass = PlaceClass.Open,
    /** The confined profile's route check of the loaded mission; null in the open profile or with no mission. */
    val routeCheck: RouteCheck? = null,
    /** The RC declaration; a pilot that does not report one flies with an RC transmitter. */
    val rcReceiver: RcReceiver = RcReceiver.Present,
    /** How long a silent operator link may last before [linkLossAction], when the pilot reports it. */
    val linkLossS: Float? = null,
    /** The limits the pilot flies now (the profile capped by the FC, the vehicle and the place); an older agent sends none. */
    val profileLimits: ProfileLimits? = null,
    /** Present while the operator's sticks own the vehicle. */
    val stick: StickCommand? = null,
    /** The limits that cut the asked velocity since the previous frame (`command.limits`, first bound first); empty while it flies none. */
    val commandLimits: List<String> = emptyList(),
    /** The simulator's clock while the pilot follows one (the "SIM ×N" badge); null on wall time and from an older agent. */
    val simClock: SimClock? = null,
) {
    /** Declared to fly with no RC transmitter: the app's controls are the only way to stop the drone. */
    val noRc: Boolean get() = rcReceiver == RcReceiver.Absent

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

/** The pilot's horizontal speed cap: why ([reason], e.g. `gnss-denied`, `confined-near`) and the limit [vHMps] in m/s. */
data class SpeedCap(val reason: String, val vHMps: Float)

/**
 * The charge a low-battery return home needs: the return fires at [needPct] %, set by the
 * estimate of the way home ([basis] `energy`) or the fixed floor (`floor`); [returnS] the
 * seconds home, countdown included.
 */
data class BatteryReserve(val needPct: Int, val basis: String, val returnS: Int)

/**
 * The effective limits the pilot flies: [profile]'s row capped by the FC, the vehicle and the
 * place; [vHMps] in m/s; [marginM] the obstacle margin it keeps (null or 0 when not sent).
 */
data class ProfileLimits(val profile: PilotProfile?, val vHMps: Float, val marginM: Float? = null)

/** The stick command the pilot flew this tick; [inputAgeMs] is the age of the stick frame it flew. */
data class StickCommand(val inputAgeMs: Long)

/**
 * The simulator's clock the pilot follows: [speed] its pacing (0 as fast as it can), null when the
 * frame did not say. A run on a simulator's clock never reads as real time.
 */
data class SimClock(val speed: Double?) {
    /** The badge, as every surface writes it: "SIM ×3", "SIM ×0.5", "SIM ×max" or "SIM". */
    val label: String get() {
        val s = speed ?: return "SIM"
        if (s <= 0.0) return "SIM ×max"
        val n = Math.round(s * 10.0) / 10.0
        return if (n % 1.0 == 0.0) "SIM ×${n.toLong()}" else "SIM ×$n"
    }
}

/**
 * The pilot does not trust the horizontal position: [reason] is `flow_disagrees`,
 * `extnav_lost` or `extnav_degraded`; [detail] Navigation's own reason when it named one;
 * [flowMps] / [estimateMps] the measured flow speed and the estimate's speed when sent.
 */
data class PositionUnverified(val reason: String, val detail: String?, val flowMps: Float?, val estimateMps: Float?)

/**
 * Leaves or a ceiling the pilot sees overhead along the way of lease [leaseId]'s goal: the
 * airframe passes under it up to [flyUnderAglM] m over the ground, [aheadM] m (horizontal)
 * along the way.
 */
data class OverheadAhead(val leaseId: Long, val flyUnderAglM: Float, val aheadM: Float)

/** The world model the pilot sees obstacles with, by its wire name. */
enum class SensingTier(val wire: String) {
    /** A 3D lidar's voxel map. */
    Lidar3d("lidar3d"),

    /** A depth camera's voxel map, known only inside the camera's view. */
    Depth("depth"),

    /** Learned depth from a camera. */
    Camera("camera"),

    /** A planar lidar's scan. */
    Planar("planar");

    companion object {
        fun of(wire: String?): SensingTier? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The pilot's sensing: its [tier], the range it sees clear along its motion ([clearRangeM], m)
 * and the horizontal speed that still stops inside that range ([speedLimitMps], m/s).
 */
data class Sensing(val tier: SensingTier, val clearRangeM: Float, val speedLimitMps: Float)

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

        /** Skips the stalled waypoint now (the mission continues with the next item). */
        const val MISSION_SKIP = "mission_skip"

        fun profile(p: PilotProfile) = PilotAction("profile:${p.wire}")
        fun controlMode(m: ControlMode) = PilotAction("control_mode:${m.wire}")
        fun avoid(m: AvoidMode) = PilotAction("avoid:${m.wire}")

        /** VTOL transition: to forward flight when [toFixedWing], else to hover. */
        fun vtol(toFixedWing: Boolean) = PilotAction(if (toFixedWing) "vtol:fw" else "vtol:mc")

        /** The place class (refused while armed); the pilot keeps it across restarts. */
        fun placeClass(c: PlaceClass) = PilotAction("place_class:${c.wire}")

        /** Clears the connected flight controller's learned airframe record after a rebalance. */
        const val AIRFRAME_RESET = "airframe_reset"

        /** The launch after-actions a take-off sends: hold where it climbed, or fly the loaded mission. */
        const val AFTER_HOLD = "hold"
        const val AFTER_MISSION = "mission"

        /** The supervised launch: spool, level lift-off, climb to [altM], then [after]. */
        fun launch(after: String, altM: Float) = PilotAction(LAUNCH, mapOf("after" to after, "alt_m" to altM))
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
