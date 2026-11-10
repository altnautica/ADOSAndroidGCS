package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.demo.DemoFc
import com.altnautica.gcs.core.demo.DemoScenario
import com.altnautica.gcs.core.demo.DemoScenarioStore
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.enums.MavModeFlag
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
 * A [DemoScenario] pinned in [scenarios] lays its fields over this live state (see [pinFields]);
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

    private var session: Session? = null
    private var gate: Gate? = null
    private var launch: Launch? = null

    /** The pilot's own GUIDED request, until the flight controller reports it. */
    private var guidedSentMs: Long? = null

    /** The scenario the last frame was built under, and when it was pinned. */
    private var pinnedScenario: DemoScenario? = null
    private var pinnedAtMs = 0L

    /** When a surface's `goto` was accepted under [DemoScenario.FlyHere]; the owner shows `goto` for [GOTO_SHOWN_MS]. */
    private var gotoAtMs: Long? = null

    private val stalled: Boolean get() = scenarios.pinned.value == DemoScenario.PilotStalled

    private class Session(val src: String, val id: Long, var lastInputMs: Long?, var axes: FloatArray = FloatArray(4))

    /** Pending until the sticks have been centred for [GATE_MS]. */
    private class Gate(var centredSinceMs: Long? = null, var pending: Boolean = true, var progress: Float = 0f)

    private class Launch(
        val after: String,
        val altM: Float,
        var step: String,
        var stepMs: Long,
        var error: String? = null,
        var takeoffSentMs: Long? = null,
        var endedMs: Long? = null,
    )

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
            Snapshot(if (stalled) null else frameOf(t, data), viewOf(data, t))
        }
    }

    /** What the demo's layer and follow feeds follow: the state the pilot reports, pin included. */
    fun view(): DemoPilotView {
        val t = now()
        return synchronized(lock) { viewOf(state(t), t) }
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
                "session.take" -> session = Session(src, sessionId, null)
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
        val s = if (current?.src == src) current else Session(src, id, null).also { session = it }
        s.lastInputMs = t
        val axes = FloatArray(4) { i -> (axesJson?.getOrNull(i) as? JsonPrimitive)?.floatOrNull ?: 0f }
        s.axes = axes
        val deflect = axes.maxOf { abs(it) }
        if (controlMode == "ados" && fc.armed && inGuided(t) && deflect > PREEMPT_AXIS && (owner == IDLE || owner == FC_MODE)) {
            owner = PILOT
            gate = Gate()
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

    /** Runs [name]; null on success, otherwise the refusal reason. */
    private fun act(name: String, args: JsonObject?, t: Long): String? {
        when {
            name.startsWith("profile:") -> profile = name.removePrefix("profile:").takeIf { it in PROFILES } ?: return "bad_value"
            name.startsWith("control_mode:") -> controlMode = name.removePrefix("control_mode:").takeIf { it in CONTROL_MODES } ?: return "bad_value"
            name.startsWith("avoid:") -> avoid = name.removePrefix("avoid:").takeIf { it in AVOID_MODES } ?: return "bad_value"
            name.startsWith("vtol:") -> return "vehicle_class"
            else -> when (name) {
                "arm" -> {
                    if (fc.armed) return "armed"
                    notReady(t)?.let { return it }
                    armFc()
                }
                "disarm" -> {
                    if (!fc.armed) return "disarmed"
                    if (!landed()) return "not_landed"
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
                    if (!fc.armed) return "disarmed"
                    launch = null
                    gotoAtMs = null
                    fc.command(MavCmd.MAV_CMD_NAV_LAND)
                }
                "rth" -> {
                    if (!fc.armed) return "disarmed"
                    launch = null
                    gotoAtMs = null
                    fc.command(MavCmd.MAV_CMD_NAV_RETURN_TO_LAUNCH)
                }
                "goto" -> return goto(args, t)
                "pause" -> {
                    if (!fc.armed) return null
                    val l = launch
                    if (l != null && l.endedMs == null) {
                        // Pause aborts a launch: disarm on the ground, hold in the air.
                        l.error = "aborted"
                        l.endedMs = t
                        if (landed()) {
                            fc.command(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, 0f)
                            return null
                        }
                    }
                    holdAsPilot(t)
                }
                "take_control" -> {
                    if (!fc.armed) return "disarmed"
                    holdAsPilot(t)
                    gate = Gate()
                }
                "hand_to_fc" -> {
                    if (!fc.armed) return "disarmed"
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

    private fun startLaunch(after: String, altM: Float, checks: Boolean, t: Long): String? {
        if (fc.armed && !landed()) return "airborne"
        if (!fc.armed) notReady(t)?.let { return it }
        launch = Launch(after, altM.coerceIn(1f, MAX_ALT_M), if (checks) "checks" else "arm", t)
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

    private fun driveLaunch(l: Launch, armed: Boolean, t: Long) {
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

    private fun fail(l: Launch, error: String, t: Long) {
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
        val live = liveState(t)
        if (scenario == null) live else JsonObject(live + pinFields(scenario, t - pinnedAtMs))
    }

    private fun liveState(t: Long): JsonObject {
        val s = session
        val age = s?.lastInputMs?.let { t - it }
        val stale = s != null && owner == PILOT && (age == null || age > INPUT_STALE_MS)
        val mode = fc.mode
        return buildJsonObject {
            put("owner", owner)
            put("owner_label", ownerLabel(owner))
            put("skill", JsonNull)
            put("vehicle_class", "multirotor")
            put("control_mode", controlMode)
            put("profile", profile)
            put("avoid", avoid)
            put("fc_mode", mode)
            put("fc_mode_name", modeName(mode))
            put("armed", fc.armed)
            put("landed", landed())
            put("vtol_transitioning", false)
            put("input_src", s?.src?.let(::JsonPrimitive) ?: JsonNull)
            put("input_age_ms", age?.let(::JsonPrimitive) ?: JsonNull)
            put("hold_reason", if (stale) JsonPrimitive(HOLD_CONTROLLER_LOST) else JsonNull)
            put("avoid_state", if (avoid == "off") "off" else "clear")
            put("min_clearance_m", 3.2)
            put("goal_ned", JsonNull)
            putJsonArray("plan_ned") {}
            put("setpoint_ned", JsonNull)
            putJsonArray("readiness") {
                add(FC_PROFILE_OK)
                add(readiness("position", true, "block", "Position estimate available"))
            }
            put("link_loss_in_s", JsonNull)
            put("link_loss_action", LINK_LOSS_ACTION)
            putJsonObject("mission") {
                put("state", "none")
                put("seq", 0)
                put("total", 0)
                put("item_reached", JsonNull)
                put("id", JsonNull)
                put("source", "fallback")
                put("age_ms", 0)
                put("landing_seq", false)
            }
            if (s != null) {
                putJsonObject("input_session") {
                    put("src", s.src)
                    put("label", if (s.src == SRC_ANDROID) "Android" else s.src)
                }
            } else {
                put("input_session", JsonNull)
            }
            val g = gate
            if (g != null) {
                putJsonObject("gate") {
                    put("pending", g.pending)
                    put("progress", g.progress.toDouble())
                }
            } else {
                put("gate", JsonNull)
            }
            val l = launch
            if (l != null) {
                putJsonObject("sequence") {
                    put("step", l.step)
                    put("after", l.after)
                    put("error", l.error?.let(::JsonPrimitive) ?: JsonNull)
                }
            } else {
                put("sequence", JsonNull)
            }
            put("banner", JsonNull)
            put("rth_countdown_s", JsonNull)
            putJsonArray("surface_ops") {}
            // Height above ground follows the flight controller's height; 0 on the ground.
            put("agl_m", if (landed()) 0.0 else round1(fc.altitudeM))
            put("agl_source", AGL_SOURCE)
            putJsonArray("statustext") {}
        }
    }

    /** The fields [scenario] lays over the live state, [sinceMs] after it was pinned. */
    private fun pinFields(scenario: DemoScenario, sinceMs: Long): JsonObject = buildJsonObject {
        val hold = scenario.holdReason
        val refusal = scenario.gotoRefusal
        when {
            hold != null -> holding(hold)
            refusal != null -> refusing(refusal)
            else -> when (scenario) {
                DemoScenario.ReadinessBlocked -> {
                    grounded(armed = false)
                    putJsonArray("readiness") {
                        add(FC_PROFILE_OK)
                        add(readiness(READINESS_BLOCK_ID, false, "block", "No position estimate"))
                    }
                }
                DemoScenario.MissionActive -> {
                    flying(MISSION_FC, AUTO)
                    mission("active", MISSION_SEQ, MISSION_TOTAL)
                }
                DemoScenario.MissionPaused -> {
                    flying(PILOT, GUIDED)
                    mission("paused", MISSION_SEQ, MISSION_TOTAL)
                }
                DemoScenario.MissionComplete -> {
                    flying(MISSION_FC, AUTO)
                    mission("complete", MISSION_TOTAL, MISSION_TOTAL)
                }
                DemoScenario.FcFailsafeRtl -> {
                    flying(FC_MODE, RTL)
                    banner("pilot.banner.fc_mode_change", "mode" to modeName(RTL), "cause" to "fc")
                }
                DemoScenario.LinkLoss -> {
                    flying(PILOT, GUIDED)
                    put("link_loss_in_s", round1((LINK_LOSS_MS - sinceMs % LINK_LOSS_MS) / 1000.0))
                }
                DemoScenario.NoGnss -> putJsonArray("readiness") {
                    add(FC_PROFILE_OK)
                    add(readiness("position", true, "block", "Position from the EKF origin only"))
                    add(readiness("gps", false, "warn", "No GNSS fix"))
                }
                DemoScenario.StatusTexts -> putJsonArray("statustext") {
                    STATUS_TEXTS.forEachIndexed { i, text ->
                        add(buildJsonObject {
                            put("severity", SEVERITY_WARNING)
                            put("text", text)
                            put("age_ms", i * STATUS_TEXT_SPACING_MS + sinceMs % STATUS_TEXT_WRAP_MS)
                        })
                    }
                }
                DemoScenario.Follow -> {
                    flying(SKILL, GUIDED, FOLLOW_LABEL)
                    putJsonObject("skill") {
                        put("plugin_id", FOLLOW_PLUGIN_ID)
                        put("skill_id", FOLLOW_SKILL_ID)
                        put("label", FOLLOW_LABEL)
                    }
                }
                DemoScenario.FlyHere -> {
                    if (gotoAtMs != null) flying(GOTO, GUIDED) else flying(PILOT, GUIDED)
                    // The pilot flies a surface's point only in ADOS control; otherwise the surface uses the FC.
                    putJsonArray("surface_ops") { if (controlMode == "ados") add(GOTO) }
                }
                else -> Unit
            }
        }
    }

    /** Airborne in a pilot hold for [reason]. */
    private fun JsonObjectBuilder.holding(reason: String) {
        flying(PILOT, GUIDED)
        put("hold_reason", reason)
        when (reason) {
            HOLD_CONTROLLER_LOST -> banner("pilot.banner.controller_lost")
            "obstacle-close" -> {
                put("avoid_state", "blocked")
                put("min_clearance_m", 0.9)
                banner("pilot.banner.obstacle_close")
            }
            "blocked" -> put("avoid_state", "blocked")
        }
    }

    /** Offers `goto` from the state each refusal [code] stands for. */
    private fun JsonObjectBuilder.refusing(code: String) {
        when (code) {
            "not_armed" -> grounded(armed = false)
            "landed" -> grounded(armed = true)
            "tuning" -> flying(TUNING, GUIDED)
            "launching" -> {
                put("owner", IDLE)
                put("owner_label", ownerLabel(IDLE))
                put("fc_mode", GUIDED)
                put("fc_mode_name", modeName(GUIDED))
                put("armed", true)
                put("landed", false)
                put("agl_m", 1.2)
                putJsonObject("sequence") {
                    put("step", "takeoff")
                    put("after", "hold")
                    put("error", JsonNull)
                }
            }
            "control_mode" -> {
                flying(FC_MODE, LOITER)
                put("control_mode", "fc_manual")
            }
            "failsafe" -> {
                flying(FC_MODE, RTL)
                banner("pilot.banner.fc_mode_change", "mode" to modeName(RTL), "cause" to "fc")
            }
            else -> flying(PILOT, GUIDED)
        }
        putJsonArray("surface_ops") { add(GOTO) }
    }

    private fun JsonObjectBuilder.flying(owner: String, mode: Int, label: String = ownerLabel(owner)) {
        put("owner", owner)
        put("owner_label", label)
        put("fc_mode", mode)
        put("fc_mode_name", modeName(mode))
        put("armed", true)
        put("landed", false)
        put("agl_m", PIN_HEIGHT_M)
    }

    private fun JsonObjectBuilder.grounded(armed: Boolean) {
        put("owner", IDLE)
        put("owner_label", ownerLabel(IDLE))
        put("armed", armed)
        put("landed", true)
        put("agl_m", 0.0)
    }

    private fun JsonObjectBuilder.mission(state: String, seq: Int, total: Int) = putJsonObject("mission") {
        put("state", state)
        put("seq", seq)
        put("total", total)
        put("item_reached", if (state == "complete") total else seq - 1)
        put("id", DEMO_MISSION_ID)
        put("source", "mission_state")
        put("age_ms", 120)
        put("landing_seq", false)
    }

    private fun JsonObjectBuilder.banner(key: String, vararg args: Pair<String, String>) = putJsonObject("banner") {
        put("text_key", key)
        putJsonObject("args") { args.forEach { (k, v) -> put(k, v) } }
        put("until_ms", JsonNull)
    }

    /** The view of [data] the other demo feeds follow, at [t]. */
    private fun viewOf(data: JsonObject, t: Long): DemoPilotView {
        val skill = (data["skill"] as? JsonObject)?.text("skill_id")
        val mission = data["mission"] as? JsonObject ?: JsonObject(emptyMap())
        val active = mission.text("state") == "active"
        return DemoPilotView(
            followRunning = data.text("owner") == SKILL && skill == FOLLOW_SKILL_ID,
            mission = mission,
            missionEtaS = if (active) round1((MISSION_ETA_MS - (t - pinnedAtMs) % MISSION_ETA_MS) / 1000.0) else null,
            heightM = (data["agl_m"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
        )
    }

    private fun ownerLabel(owner: String) = if (owner == PILOT) "PILOT · ${profile.uppercase()}" else owner.uppercase()

    private fun modeName(mode: Int) = MODE_NAMES[mode] ?: "MODE $mode"

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun readiness(id: String, ok: Boolean, severity: String, msg: String) = buildJsonObject {
        put("id", id)
        put("ok", ok)
        put("severity", severity)
        put("msg", msg)
    }

    private companion object {
        const val TICK_MS = 100L
        const val INPUT_STALE_MS = 300L
        const val SESSION_LIVE_MS = 1_000L
        const val GATE_MS = 500L
        const val GATE_CENTRED = 0.1f
        const val PREEMPT_AXIS = 0.3f
        const val STEP_TIMEOUT_MS = 3_000L
        const val TAKEOFF_TIMEOUT_MS = 60_000L
        const val RESEND_MS = 1_000L
        const val MODE_SETTLE_MS = 1_500L
        const val SEQUENCE_SHOWN_MS = 5_000L
        const val ARRIVED_FRACTION = 0.9f
        const val LANDED_ALT_M = 0.3
        const val TAKEOFF_ALT_M = 1.5f
        const val LAUNCH_ALT_M = 3f
        const val MAX_ALT_M = 120f
        const val FORCE_DISARM = 21196f
        const val SRC_ANDROID = "android"

        const val IDLE = "idle"
        const val PILOT = "pilot"
        const val FC_MODE = "fc_mode"
        const val MISSION_FC = "mission_fc"
        const val SKILL = "skill"
        const val GOTO = "goto"
        const val TUNING = "tuning"

        const val HOLD_CONTROLLER_LOST = "controller-lost"
        const val LINK_LOSS_ACTION = "rth"
        const val AGL_SOURCE = "rangefinder"
        /** The readiness item the readiness pin fails. */
        const val READINESS_BLOCK_ID = "position"
        val FC_PROFILE_OK = buildJsonObject {
            put("id", "fc_profile")
            put("ok", true)
            put("severity", "block")
            put("msg", "FC failsafe parameters match the ADOS profile")
        }

        /** Height a flying pin reports, metres. */
        const val PIN_HEIGHT_M = 15.0
        const val GOTO_SHOWN_MS = 20_000L
        const val LINK_LOSS_MS = 10_000L
        const val MISSION_ETA_MS = 42_000L
        const val MISSION_SEQ = 4
        const val MISSION_TOTAL = 6
        const val DEMO_MISSION_ID = 1
        const val FOLLOW_PLUGIN_ID = "com.altnautica.follow-me"
        const val FOLLOW_SKILL_ID = "follow-me"
        const val FOLLOW_LABEL = "Follow-Me"

        /** MAV_SEVERITY_WARNING. */
        const val SEVERITY_WARNING = 4
        const val STATUS_TEXT_SPACING_MS = 1_500L
        const val STATUS_TEXT_WRAP_MS = 20_000L
        val STATUS_TEXTS = listOf(
            "PreArm: Compass not calibrated",
            "PreArm: RC not calibrated",
            "PreArm: Battery 1 below minimum arming voltage",
            "PreArm: Throttle below failsafe",
            "PreArm: Hardware safety switch",
            "PreArm: Logging failed",
            "Vibration compensation ON",
            "EKF variance",
            "PreArm: AHRS: waiting for the attitude and heading reference to settle; keep the vehicle still and level until the estimate converges before arming",
        )

        const val ALTHOLD = 2
        const val AUTO = 3
        const val GUIDED = 4
        const val LOITER = 5
        const val RTL = 6
        const val LAND = 9
        const val POSHOLD = 16
        const val BRAKE = 17
        val MODE_NAMES = mapOf(
            0 to "STABILIZE", ALTHOLD to "ALT_HOLD", AUTO to "AUTO", GUIDED to "GUIDED", LOITER to "LOITER",
            RTL to "RTL", 7 to "CIRCLE", LAND to "LAND", POSHOLD to "POSHOLD", BRAKE to "BRAKE",
        )
        /** ArduCopter modes the flight controller arms in. */
        val ARMABLE_MODES = setOf(0, 1, ALTHOLD, GUIDED, LOITER, 13, POSHOLD)
        val AFTERS = setOf("hold", "skill", "mission", "goto")
        val PROFILES = setOf("cine", "normal", "sport")
        val CONTROL_MODES = setOf("ados", "fc_manual", "elrs")
        val AVOID_MODES = setOf("brake", "bypass", "off")
    }
}

/** What the demo's layer and follow feeds follow from the pilot's reported state. */
internal class DemoPilotView(
    /** The follow skill flies the vehicle: the follow, track and re-acquire feeds run. */
    val followRunning: Boolean,
    /** The pilot frame's `mission` block. */
    val mission: JsonObject,
    /** Seconds to the next waypoint while the mission is active; null otherwise. */
    val missionEtaS: Double?,
    /** Height above ground, metres; 0 on the ground. */
    val heightM: Double,
) {
    val missionState: String? get() = (mission["state"] as? JsonPrimitive)?.contentOrNull
    val missionSeq: Int get() = (mission["seq"] as? JsonPrimitive)?.intOrNull ?: 0
    val missionTotal: Int get() = (mission["total"] as? JsonPrimitive)?.intOrNull ?: 0

    /** The mission route draws only while the mission is active or paused. */
    val missionRoute: Boolean get() = missionState == "active" || missionState == "paused"
}
