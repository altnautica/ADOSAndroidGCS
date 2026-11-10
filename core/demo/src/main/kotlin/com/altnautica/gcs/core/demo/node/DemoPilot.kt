package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.demo.DemoFc
import com.altnautica.gcs.core.demo.DemoScenario
import com.altnautica.gcs.core.demo.DemoScenarioStore
import com.altnautica.gcs.core.demo.node.DemoPilotWire.ALTHOLD
import com.altnautica.gcs.core.demo.node.DemoPilotWire.AUTO
import com.altnautica.gcs.core.demo.node.DemoPilotWire.FC_MODE
import com.altnautica.gcs.core.demo.node.DemoPilotWire.GUIDED
import com.altnautica.gcs.core.demo.node.DemoPilotWire.IDLE
import com.altnautica.gcs.core.demo.node.DemoPilotWire.LANDED_ALT_M
import com.altnautica.gcs.core.demo.node.DemoPilotWire.LOITER
import com.altnautica.gcs.core.demo.node.DemoPilotWire.MISSION_FC
import com.altnautica.gcs.core.demo.node.DemoPilotWire.PILOT
import com.altnautica.gcs.core.demo.node.DemoPilotWire.POSHOLD
import com.altnautica.gcs.core.demo.node.DemoPilotWire.READINESS_BLOCK_ID
import com.altnautica.gcs.core.demo.node.DemoPilotWire.number
import com.altnautica.gcs.core.demo.node.DemoPilotWire.text
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.enums.MavModeFlag
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put

/**
 * The demo drone's onboard pilot. It answers the pilot channel's client frames the way the
 * vehicle's pilot does and commands the demo flight controller ([fc]) over MAVLink, so
 * armed, landed and the flight mode are the flight controller's:
 * - `arm` arms (disarmed only); `takeoff` and `launch` run the launch sequence (arm, GUIDED,
 *   NAV_TAKEOFF, then the after-takeoff action at 90 % of the altitude), which ends with the
 *   owner `pilot` for a hold;
 * - stick input in ADOS Pilot mode while armed in GUIDED takes the owner from `idle` or
 *   `fc_mode` to `pilot`, then holds until the sticks are centred (the handover gate);
 * - the first live input session owns the sticks, a second source is ignored until it takes
 *   the session, and `input_src` names the owning surface;
 * - an armed flight controller in any other mode makes the owner `fc_mode` (AUTO:
 *   `mission_fc`), and disarming makes it `idle`.
 *
 * A [DemoScenario] pinned in [scenarios] lays its fields over this live state (see
 * [DemoPilotPins]); while pinned, the actions answer from the pinned `armed` and `landed`,
 * so the pilot never refuses what its own frame offers. A failsafe pin refuses `pause`.
 * [DemoScenario.PilotStalled] stops the frames and the action answers instead.
 */
internal class DemoPilot(
    private val fc: DemoFc,
    /** Told each change of owner, input source or launch step, as one line (the demo's flight log). */
    private val onTransition: (String) -> Unit = {},
    private val scenarios: DemoScenarioStore = DemoScenarioStore(),
    private val now: () -> Long,
) {
    private var lastSummary: String? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private var owner = IDLE
    private var controlMode = "ados"
    private var profile = "normal"
    private var avoid = "brake"

    private var session: PilotSession? = null
    private var gate: PilotGate? = null
    private var launch: PilotLaunch? = null

    /** The pilot's own GUIDED request, until the flight controller reports it. */
    private var guidedSentMs: Long? = null

    /** The scenario the last frame was built under, and when it was pinned. */
    private var pinnedScenario: DemoScenario? = null
    private var pinnedAtMs = 0L

    /** When a surface's `goto` was accepted under [DemoScenario.FlyHere]; the owner shows `goto` for [GOTO_SHOWN_MS]. */
    private var gotoAtMs: Long? = null

    private val stalled: Boolean get() = scenarios.pinned.value == DemoScenario.PilotStalled

    /** Pilot frames at 10 Hz (none while stalled) plus an `action_result` for each action in [outgoing]. */
    fun stream(outgoing: Flow<String>): Flow<String> = channelFlow {
        launch { outgoing.collect { text -> handle(text)?.let { send(it) } } }
        while (true) {
            snapshot().frame?.let { send(it) }
            delay(TICK_MS)
        }
    }

    /** One `{kind:"pilot", ts_ms, data}` frame of the current state. */
    fun frame(): String {
        val t = now()
        return frameOf(t, state(t))
    }

    /** The current pilot frame (null while stalled) and the view the other demo feeds follow, from one state. */
    fun snapshot(): Snapshot {
        val t = now()
        return synchronized(lock) {
            val data = state(t)
            Snapshot(if (stalled) null else frameOf(t, data), pilotView(data, t - pinnedAtMs))
        }
    }

    /** What the demo's layer and follow feeds follow: the state the pilot reports, pin included. */
    fun view(): DemoPilotView {
        val t = now()
        return synchronized(lock) { pilotView(state(t), t - pinnedAtMs) }
    }

    class Snapshot(val frame: String?, val view: DemoPilotView)

    private fun frameOf(t: Long, data: JsonObject): String = buildJsonObject {
        put("kind", "pilot")
        put("ts_ms", t)
        put("data", data)
    }.toString()

    /** Applies one client frame; returns the `action_result` frame for an action. A stalled pilot reads nothing. */
    fun handle(text: String): String? {
        if (stalled) return null
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val src = o.text("src") ?: return null
        val sessionId = (o["session"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
        val t = now()
        synchronized(lock) {
            when (o.text("op")) {
                "input" -> onInput(src, sessionId, o["axes"] as? JsonArray, t)
                "release" -> if (session?.src == src) {
                    session = null
                    gate = null
                }
                "session.take" -> session = PilotSession(src, sessionId, null)
                "action" -> {
                    val name = o.text("name") ?: return null
                    val err = act(name, o["args"] as? JsonObject, t)
                    return buildJsonObject {
                        put("kind", "action_result")
                        put("name", name)
                        o.text("id")?.let { put("id", it) }
                        put("ok", err == null)
                        if (err != null) put("err", err)
                    }.toString()
                }
                else -> Unit
            }
        }
        return null
    }

    private fun onInput(src: String, id: Long, axesJson: JsonArray?, t: Long) {
        val current = session
        // The first live session owns the sticks; another surface waits for session.take.
        if (current != null && current.src != src && current.lastInputMs?.let { t - it <= SESSION_LIVE_MS } != false) return
        val s = if (current?.src == src) current else PilotSession(src, id, null).also { session = it }
        s.lastInputMs = t
        val axes = FloatArray(4) { i -> (axesJson?.getOrNull(i) as? JsonPrimitive)?.floatOrNull ?: 0f }
        s.axes = axes
        val deflect = axes.maxOf { abs(it) }
        if (controlMode == "ados" && fc.armed && inGuided(t) && deflect > PREEMPT_AXIS && (owner == IDLE || owner == FC_MODE)) {
            owner = PILOT
            gate = PilotGate()
        }
        gate?.let { g ->
            if (deflect < GATE_CENTRED) {
                val since = g.centredSinceMs ?: t.also { g.centredSinceMs = it }
                g.progress = ((t - since).toFloat() / GATE_MS).coerceAtMost(1f)
                if (t - since >= GATE_MS) gate = null
            } else {
                g.centredSinceMs = null
                g.progress = 0f
            }
        }
    }

    /** Runs [name]; null on success, otherwise the refusal reason. Armed and landed are the reported (pinned) ones. */
    private fun act(name: String, args: JsonObject?, t: Long): String? {
        when {
            name.startsWith("profile:") -> profile = name.removePrefix("profile:").takeIf { it in PROFILES } ?: return "bad_value"
            name.startsWith("control_mode:") -> controlMode = name.removePrefix("control_mode:").takeIf { it in CONTROL_MODES } ?: return "bad_value"
            name.startsWith("avoid:") -> avoid = name.removePrefix("avoid:").takeIf { it in AVOID_MODES } ?: return "bad_value"
            name.startsWith("vtol:") -> return "vehicle_class"
            else -> when (name) {
                "arm" -> {
                    if (armed(t)) return "armed"
                    notReady(t)?.let { return it }
                    armFc()
                }
                "disarm" -> {
                    if (!armed(t)) return "disarmed"
                    if (!grounded(t)) return "not_landed"
                    fc.command(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, 0f)
                }
                "kill" -> {
                    launch = null
                    fc.command(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, 0f, FORCE_DISARM)
                }
                "takeoff" -> return startLaunch("hold", altArg(args) ?: TAKEOFF_ALT_M, checks = false, t)
                "launch" -> {
                    val after = args?.text("after") ?: "hold"
                    if (after !in AFTERS) return "bad_after"
                    return startLaunch(after, altArg(args) ?: LAUNCH_ALT_M, checks = true, t)
                }
                "land" -> {
                    if (!armed(t)) return "disarmed"
                    launch = null
                    gotoAtMs = null
                    fc.command(MavCmd.MAV_CMD_NAV_LAND)
                }
                "rth" -> {
                    if (!armed(t)) return "disarmed"
                    launch = null
                    gotoAtMs = null
                    fc.command(MavCmd.MAV_CMD_NAV_RETURN_TO_LAUNCH)
                }
                "goto" -> return goto(args, t)
                "pause" -> return pause(t)
                "take_control" -> {
                    if (!armed(t)) return "disarmed"
                    holdAsPilot(t)
                    gate = PilotGate()
                }
                "hand_to_fc" -> {
                    if (!armed(t)) return "disarmed"
                    setMode(
                        when (args?.text("mode")) {
                            "poshold" -> POSHOLD
                            "althold" -> ALTHOLD
                            else -> LOITER
                        },
                    )
                    owner = FC_MODE
                    controlMode = "fc_manual"
                }
                "resume", "rth_cancel" -> Unit
                else -> return "unknown_action"
            }
        }
        return null
    }

    /** Pause holds as the pilot; refused while the flight controller is in its own failsafe. */
    private fun pause(t: Long): String? {
        if (DemoPilotPins.failsafe(pinned(t))) return "failsafe"
        if (!armed(t)) return null
        val l = launch
        if (l != null && l.endedMs == null) {
            // Pause aborts a launch: disarm on the ground, hold in the air.
            l.error = "aborted"
            l.endedMs = t
            if (grounded(t)) {
                fc.command(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, 0f)
                return null
            }
        }
        holdAsPilot(t)
        return null
    }

    /** `not_ready:<id>` while the readiness pin fails a block item in ADOS control, as the pilot refuses to arm. */
    private fun notReady(t: Long): String? =
        if (controlMode == "ados" && pinned(t) == DemoScenario.ReadinessBlocked) "not_ready:$READINESS_BLOCK_ID" else null

    /**
     * A surface's "fly here" (`{lat, lon, alt_m}`), offered only while a scenario pins `goto` in
     * `surface_ops`: a goto refusal pin refuses with its code; under [DemoScenario.FlyHere] the
     * arguments and the control mode are checked as the vehicle's pilot does, then the owner
     * shows `goto`.
     */
    private fun goto(args: JsonObject?, t: Long): String? {
        val scenario = pinned(t)
        if (scenario == null || !scenario.offersGoto) return "unknown_action"
        scenario.gotoRefusal?.let { return it }
        val lat = args?.number("lat")
        val lon = args?.number("lon")
        val alt = args?.number("alt_m")
        if (lat == null || lon == null || alt == null || lat !in -90.0..90.0 || lon !in -180.0..180.0 || !alt.isFinite()) return "bad_args"
        if (controlMode != "ados") return "control_mode"
        gotoAtMs = t
        return null
    }

    /** The pinned scenario; a change of pin restarts its clock and drops an accepted goto. */
    private fun pinned(t: Long): DemoScenario? {
        val s = scenarios.pinned.value
        if (s != pinnedScenario) {
            pinnedScenario = s
            pinnedAtMs = t
            gotoAtMs = null
        }
        return s
    }

    private fun pins() = DemoPilotPins(controlMode, profile, gotoAccepted = gotoAtMs != null)

    /** A flag ([key]) the pinned scenario reports, or null when nothing is pinned or the pin leaves it live. */
    private fun pinnedFlag(t: Long, key: String): Boolean? {
        val scenario = pinned(t) ?: return null
        return (pins().fields(scenario, t - pinnedAtMs)[key] as? JsonPrimitive)?.booleanOrNull
    }

    /** Armed as the pilot reports it: the pin's `armed`, otherwise the flight controller's. */
    private fun armed(t: Long): Boolean = pinnedFlag(t, "armed") ?: fc.armed

    /** Landed as the pilot reports it: the pin's `landed`, otherwise from the flight controller's height. */
    private fun grounded(t: Long): Boolean = pinnedFlag(t, "landed") ?: landed()

    private fun startLaunch(after: String, altM: Float, checks: Boolean, t: Long): String? {
        if (armed(t) && !grounded(t)) return "airborne"
        if (!armed(t)) notReady(t)?.let { return it }
        launch = PilotLaunch(after, altM.coerceIn(1f, MAX_ALT_M), if (checks) "checks" else "arm", t)
        if (!fc.armed) armFc()
        return null
    }

    /** Arms, first leaving a mode the flight controller refuses to arm in (LAND, RTL, AUTO after a flight). */
    private fun armFc() {
        if (fc.mode !in ARMABLE_MODES) setMode(GUIDED)
        fc.command(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, 1f)
    }

    private fun holdAsPilot(t: Long) {
        if (!inGuided(t)) setMode(GUIDED)
        guidedSentMs = t
        gotoAtMs = null
        owner = PILOT
    }

    private fun setMode(mode: Int) {
        fc.command(MavCmd.MAV_CMD_DO_SET_MODE, MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED.toFloat(), mode.toFloat())
    }

    /** GUIDED on the flight controller, or just requested by the pilot (the mode change lands within a heartbeat). */
    private fun inGuided(t: Long): Boolean = fc.mode == GUIDED || guidedSentMs?.let { t - it < MODE_SETTLE_MS } == true

    private fun landed(): Boolean = fc.altitudeM < LANDED_ALT_M

    /** Advances the launch sequence and the owner from the flight controller's state; called once per frame. */
    private fun tick(t: Long) {
        val armed = fc.armed
        val l = launch
        if (l != null && l.endedMs == null) driveLaunch(l, armed, t)
        if (l != null && l.endedMs?.let { t - it > SEQUENCE_SHOWN_MS } == true) launch = null
        if (guidedSentMs?.let { t - it >= MODE_SETTLE_MS } == true) guidedSentMs = null
        if (gotoAtMs?.let { t - it > GOTO_SHOWN_MS } == true) gotoAtMs = null
        owner = when {
            !armed -> IDLE
            fc.mode == AUTO -> MISSION_FC
            fc.mode != GUIDED && guidedSentMs == null && owner != IDLE -> FC_MODE
            fc.mode != GUIDED && guidedSentMs == null && owner == IDLE && !landed() -> FC_MODE
            else -> owner
        }
        if (!armed) gate = null
    }

    private fun driveLaunch(l: PilotLaunch, armed: Boolean, t: Long) {
        val elapsed = t - l.stepMs
        when (l.step) {
            "checks" -> {
                l.step = "arm"
                l.stepMs = t
            }
            "arm" -> if (armed) {
                setMode(GUIDED)
                guidedSentMs = t
                l.step = "takeoff"
                l.stepMs = t
            } else if (elapsed > STEP_TIMEOUT_MS) {
                fail(l, "arm_refused", t)
            }
            "takeoff" -> {
                val height = fc.altitudeM
                if (fc.mode == GUIDED && height < LANDED_ALT_M && l.takeoffSentMs?.let { t - it >= RESEND_MS } != false) {
                    fc.command(MavCmd.MAV_CMD_NAV_TAKEOFF, p7 = l.altM)
                    l.takeoffSentMs = t
                }
                when {
                    height >= ARRIVED_FRACTION * l.altM -> {
                        // Hold, a skill and a map point all start from a pilot hold in the demo.
                        owner = PILOT
                        l.step = "done"
                        l.stepMs = t
                        l.endedMs = t
                    }
                    !armed -> fail(l, "disarmed", t)
                    elapsed > TAKEOFF_TIMEOUT_MS -> fail(l, "takeoff_timeout", t)
                }
            }
        }
    }

    private fun fail(l: PilotLaunch, error: String, t: Long) {
        l.error = error
        l.endedMs = t
    }

    private fun altArg(args: JsonObject?): Float? = (args?.get("alt_m") as? JsonPrimitive)?.doubleOrNull?.toFloat()?.takeIf { it.isFinite() && it > 0f }

    /** The pilot kind's `data`: the live state with the pinned scenario's fields laid over it. */
    private fun state(t: Long): JsonObject = synchronized(lock) {
        tick(t)
        val scenario = pinned(t)
        val summary = "owner=$owner input_src=${session?.src} armed=${fc.armed} landed=${landed()} fc_mode=${fc.mode} step=${launch?.step} pin=$scenario"
        if (summary != lastSummary) {
            lastSummary = summary
            onTransition(summary)
        }
        val live = liveFrame(
            PilotLive(owner, controlMode, profile, avoid, session, gate, launch),
            FcLive(fc.armed, fc.mode, landed(), fc.altitudeM),
            t,
        )
        if (scenario == null) live else JsonObject(live + pins().fields(scenario, t - pinnedAtMs))
    }

    private companion object {
        const val TICK_MS = 100L
        const val SESSION_LIVE_MS = 1_000L
        const val GATE_MS = 500L
        const val GATE_CENTRED = 0.1f
        const val PREEMPT_AXIS = 0.3f
        const val STEP_TIMEOUT_MS = 3_000L
        const val TAKEOFF_TIMEOUT_MS = 60_000L
        const val RESEND_MS = 1_000L
        const val MODE_SETTLE_MS = 1_500L
        const val SEQUENCE_SHOWN_MS = 5_000L
        const val GOTO_SHOWN_MS = 20_000L
        const val ARRIVED_FRACTION = 0.9f
        const val TAKEOFF_ALT_M = 1.5f
        const val LAUNCH_ALT_M = 3f
        const val MAX_ALT_M = 120f
        const val FORCE_DISARM = 21196f

        /** ArduCopter modes the flight controller arms in. */
        val ARMABLE_MODES = setOf(0, 1, ALTHOLD, GUIDED, LOITER, 13, POSHOLD)
        val AFTERS = setOf("hold", "skill", "mission", "goto")
        val PROFILES = setOf("cine", "normal", "sport")
        val CONTROL_MODES = setOf("ados", "fc_manual", "elrs")
        val AVOID_MODES = setOf("brake", "bypass", "off")
    }
}
