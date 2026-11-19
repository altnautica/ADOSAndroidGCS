package com.altnautica.gcs.core.agent.pilot

import com.altnautica.gcs.core.model.AvoidMode
import com.altnautica.gcs.core.model.AvoidState
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.HandoverGate
import com.altnautica.gcs.core.model.InputSession
import com.altnautica.gcs.core.model.LaunchSequence
import com.altnautica.gcs.core.model.LaunchStep
import com.altnautica.gcs.core.model.Liftoff
import com.altnautica.gcs.core.model.LiftoffPhase
import com.altnautica.gcs.core.model.MissionGap
import com.altnautica.gcs.core.model.MissionStall
import com.altnautica.gcs.core.model.MissionStallKind
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.MissionStatus
import com.altnautica.gcs.core.model.MissionTarget
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotBanner
import com.altnautica.gcs.core.model.PilotInput
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.model.PilotSkillRef
import com.altnautica.gcs.core.model.PilotStatusText
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.PilotVehicleClass
import com.altnautica.gcs.core.model.PositionUnverified
import com.altnautica.gcs.core.model.PlaceClass
import com.altnautica.gcs.core.model.RcReceiver
import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity
import com.altnautica.gcs.core.model.ProfileLimits
import com.altnautica.gcs.core.model.RouteCheck
import com.altnautica.gcs.core.model.RouteCheckState
import com.altnautica.gcs.core.model.RoutePinch
import com.altnautica.gcs.core.model.SpeedCap
import com.altnautica.gcs.core.model.StickCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom

/** One server frame of the pilot WebSocket (`/api/v1/pilot/stream`). */
sealed interface PilotFrame {
    /** `{kind:"pilot", ts_ms, data}` at 10 Hz. */
    data class State(val tsMs: Long?, val state: PilotState) : PilotFrame

    /** `{kind:"keepalive", ts_ms}` at 1 Hz while idle. */
    data class Keepalive(val tsMs: Long?) : PilotFrame

    /** `{kind:"action_result", name, id?, ok, err?}`: the pilot's answer to one `action`. */
    data class ActionResult(val name: String, val id: String?, val ok: Boolean, val err: String?) : PilotFrame
}

/**
 * The pilot channel's JSON wire: the `pilot` state kind (shared with the navigation
 * stream), the pilot WebSocket's server frames, and the client frames a surface sends.
 * Parsing never throws; a malformed frame or state yields null.
 */
object PilotWire {
    /** Input source name this app reports. */
    const val SRC_ANDROID = "android"

    /**
     * This app process's instance id (`sid`, 32 lowercase hex), on every client frame. The pilot
     * watches each instance's link on its own; a reconnect keeps the id, so it continues the same watch.
     */
    val PROCESS_SID: String = newSid()

    /** A random instance id: 128 bits as 32 lowercase hex digits. */
    fun newSid(): String {
        val bytes = ByteArray(SID_BYTES).also(SecureRandom()::nextBytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private const val SID_BYTES = 16

    /** WebSocket path of the pilot channel (needs a [TICKET_SCOPE] ticket). */
    const val STREAM_WS_PATH = "api/v1/pilot/stream"
    const val TICKET_SCOPE = "pilot.control"

    const val KIND_PILOT = "pilot"
    const val KIND_KEEPALIVE = "keepalive"
    const val KIND_ACTION_RESULT = "action_result"

    private val json = Json { ignoreUnknownKeys = true }

    /** The low-battery actions the pilot reports (`low_battery_action`). */
    private val LOW_BATTERY_ACTIONS = setOf("land", "rth")

    /** A server frame of the pilot WebSocket, or null when it is not one this build reads. */
    fun parseFrame(text: String, receivedAtMs: Long): PilotFrame? {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return when (o.str("kind")) {
            KIND_PILOT -> (o["data"] as? JsonObject)?.let { parseState(it, receivedAtMs) }?.let { PilotFrame.State(o.long("ts_ms"), it) }
            KIND_KEEPALIVE -> PilotFrame.Keepalive(o.long("ts_ms"))
            KIND_ACTION_RESULT -> {
                val name = o.str("name") ?: return null
                val ok = o.bool("ok") ?: return null
                PilotFrame.ActionResult(name, o.str("id"), ok, o.str("err"))
            }
            else -> null
        }
    }

    /** The `pilot` kind's `data` object; null when it carries no owner. */
    fun parseState(data: JsonObject, receivedAtMs: Long): PilotState? {
        val owner = data.str("owner") ?: return null
        return PilotState(
            owner = PilotOwner.of(owner),
            ownerLabel = data.str("owner_label"),
            skill = (data["skill"] as? JsonObject)?.let { s ->
                val plugin = s.str("plugin_id") ?: return@let null
                val skill = s.str("skill_id") ?: return@let null
                PilotSkillRef(plugin, skill, s.str("label") ?: skill)
            },
            controlMode = ControlMode.of(data.str("control_mode")),
            profile = PilotProfile.of(data.str("profile")),
            avoid = AvoidMode.of(data.str("avoid")),
            fcMode = data.long("fc_mode")?.toInt(),
            fcModeName = data.str("fc_mode_name"),
            // Absent or null while the pilot has no FC link: unknown, never a guessed "on the ground".
            armed = data.bool("armed"),
            landed = data.bool("landed"),
            inputSrc = data.str("input_src"),
            inputAgeMs = data.long("input_age_ms"),
            holdReason = data.str("hold_reason"),
            avoidState = AvoidState.of(data.str("avoid_state")),
            minClearanceM = data.float("min_clearance_m"),
            goalNed = floats(data["goal_ned"], 3),
            planNed = (data["plan_ned"] as? JsonArray).orEmpty().mapNotNull { p -> floats(p, 2)?.let { it[0] to it[1] } },
            setpointNed = floats(data["setpoint_ned"], 3),
            readiness = (data["readiness"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::readiness) },
            linkLossInS = data.float("link_loss_in_s"),
            mission = (data["mission"] as? JsonObject)?.let(::mission),
            vehicleClass = PilotVehicleClass.of(data.str("vehicle_class")),
            inputSession = (data["input_session"] as? JsonObject)?.let { s ->
                val src = s.str("src") ?: return@let null
                InputSession(src, s.str("label") ?: src, s.long("session"))
            },
            gate = (data["gate"] as? JsonObject)?.let { g ->
                HandoverGate(pending = g.bool("pending") == true, progress = (g.float("progress") ?: 0f).coerceIn(0f, 1f))
            },
            sequence = (data["sequence"] as? JsonObject)?.let { s ->
                val step = LaunchStep.of(s.str("step")) ?: return@let null
                val liftoff = (s["liftoff"] as? JsonObject)?.let { l ->
                    val phase = LiftoffPhase.of(l.str("phase")) ?: return@let null
                    Liftoff(phase, l.str("text"), l.str("trim"), l.float("tilt_deg"), l.str("detail")?.takeIf { it.isNotBlank() })
                }
                LaunchSequence(step, s.str("after"), s.str("error"), liftoff)
            },
            banner = (data["banner"] as? JsonObject)?.let { b ->
                val key = b.str("text_key") ?: return@let null
                val args = (b["args"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { k to it.content }
                }.toMap()
                PilotBanner(key, args, b.long("until_ms"))
            },
            rthCountdownS = data.float("rth_countdown_s"),
            receivedAtMs = receivedAtMs,
            surfaceOps = (data["surface_ops"] as? JsonArray).orEmpty().mapNotNullTo(mutableSetOf()) { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            aglM = data.float("agl_m"),
            aglSource = data.str("agl_source"),
            statusTexts = (data["statustext"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val text = o.str("text") ?: return@mapNotNull null
                PilotStatusText(o.long("severity")?.toInt() ?: return@mapNotNull null, text, o.long("age_ms"))
            },
            linkLossAction = data.str("link_loss_action"),
            vtolTransitioning = data.bool("vtol_transitioning") == true,
            setupStep = data.str("setup_step"),
            fcFailsafe = data.bool("fc_failsafe") == true,
            failsafeCause = data.str("failsafe_cause"),
            setupAbort = data.str("setup_abort"),
            lowBatteryAction = data.str("low_battery_action")?.takeIf { it in LOW_BATTERY_ACTIONS },
            speedCap = (data["speed_cap"] as? JsonObject)?.let { c ->
                SpeedCap(c.str("reason") ?: return@let null, c.float("v_h_mps") ?: return@let null)
            },
            positionUnverified = (data["position_unverified"] as? JsonObject)?.let { u ->
                PositionUnverified(u.str("reason") ?: return@let null, u.str("detail"), u.float("flow_mps"), u.float("estimate_mps"))
            },
            missionStall = (data["mission_stall"] as? JsonObject)?.let(::missionStall),
            // Absent on a pilot without the confined profile: open.
            placeClass = PlaceClass.of(data.str("place_class")) ?: PlaceClass.Open,
            routeCheck = (data["route_check"] as? JsonObject)?.let(::routeCheck),
            // Absent on a pilot without the declaration: an RC transmitter flies with the vehicle.
            rcReceiver = RcReceiver.of(data.str("rc_receiver")) ?: RcReceiver.Present,
            linkLossS = data.float("link_loss_s"),
            profileLimits = (data["profile_limits"] as? JsonObject)?.let { l ->
                ProfileLimits(PilotProfile.of(l.str("profile")), l.float("v_h_mps") ?: return@let null, l.float("margin_m"))
            },
            stick = (data["stick"] as? JsonObject)?.let { c -> StickCommand(c.long("input_age_ms") ?: return@let null) },
            commandLimits = ((data["command"] as? JsonObject)?.get("limits") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
        )
    }

    private fun routeCheck(o: JsonObject): RouteCheck? {
        val state = RouteCheckState.of(o.str("state")) ?: return null
        return RouteCheck(
            state = state,
            requiredM = o.double("required_m") ?: return null,
            marginM = o.double("margin_m"),
            sigmaM = o.double("sigma_m"),
            pinch = (o["pinch"] as? JsonObject)?.let { p ->
                RoutePinch(
                    fromSeq = p.long("from_seq")?.toInt() ?: return@let null,
                    toSeq = p.long("to_seq")?.toInt() ?: return@let null,
                    ned = doubles(p["ned"], 3) ?: return@let null,
                    clearanceM = p.double("clearance_m") ?: return@let null,
                    requiredM = p.double("required_m") ?: return@let null,
                )
            },
            unverifiedLegs = seqs(o["unverified_legs"]),
            unchecked = seqs(o["unchecked"]),
            truncated = o.bool("truncated") == true,
            stale = o.bool("stale") == true,
        )
    }

    /** The numeric entries of a mission-item seq array; anything else is skipped. */
    private fun seqs(e: JsonElement?): List<Int> =
        (e as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull?.toInt() }

    private fun missionStall(o: JsonObject): MissionStall? {
        val kind = MissionStallKind.of(o.str("kind")) ?: return null
        return MissionStall(
            kind = kind,
            seq = o.long("seq")?.toInt(),
            wpLabel = o.str("wp_label"),
            sinceMs = o.long("since_ms"),
            skipInS = o.float("skip_in_s"),
            windowS = o.float("window_s") ?: return null,
            returnInS = o.float("return_in_s"),
        )
    }

    private fun readiness(o: JsonObject): ReadinessItem? {
        val id = o.str("id") ?: return null
        val severity = when (o.str("severity")) {
            "block" -> ReadinessSeverity.Block
            "warn" -> ReadinessSeverity.Warn
            else -> return null
        }
        return ReadinessItem(id, o.bool("ok") == true, severity, o.str("msg").orEmpty())
    }

    private fun mission(o: JsonObject): MissionStatus? {
        val state = MissionState.of(o.str("state")) ?: return null
        return MissionStatus(
            state = state,
            seq = o.long("seq")?.toInt() ?: 0,
            total = o.long("total")?.toInt() ?: 0,
            itemReached = o.long("item_reached")?.toInt(),
            id = o.long("id"),
            fromMissionState = o.str("source") == "mission_state",
            ageMs = o.long("age_ms"),
            landingSeq = o.bool("landing_seq") == true,
            uploadsSeen = o.long("uploads_seen"),
            target = (o["target"] as? JsonObject)?.let { t ->
                MissionTarget(t.long("seq")?.toInt() ?: return@let null, t.long("cmd")?.toInt(), floats(t["ned"], 3), t.float("radius_m"))
            },
            gaps = (o["gaps"] as? JsonArray).orEmpty().mapNotNull { e ->
                val g = e as? JsonObject ?: return@mapNotNull null
                MissionGap(g.long("seq")?.toInt() ?: return@mapNotNull null, g.str("reason").orEmpty(), g.long("at_ms"))
            },
        )
    }

    /** `{op:"input", src, sid, session, seq, t_ms, axes:[roll,pitch,thr,yaw], buttons}`. */
    fun input(src: String, sid: String, session: Long, seq: Long, tMs: Long, frame: PilotInput): String = buildJsonObject {
        put("op", "input")
        put("src", src)
        put("sid", sid)
        put("session", session)
        put("seq", seq)
        put("t_ms", tMs)
        put(
            "axes",
            buildJsonArray {
                add(JsonPrimitive(frame.roll))
                add(JsonPrimitive(frame.pitch))
                add(JsonPrimitive(frame.throttle))
                add(JsonPrimitive(frame.yaw))
            },
        )
        put("buttons", frame.buttons)
    }.toString()

    /** `{op:"release", src, sid, session}`: the pilot brakes to a hold. */
    fun release(src: String, sid: String, session: Long): String = srcSession("release", src, sid, session)

    /** `{op:"session.take", src, sid, session}`: takes the stick session from another surface. */
    fun sessionTake(src: String, sid: String, session: Long): String = srcSession("session.take", src, sid, session)

    /** `{op:"hb", src, sid}`: keeps this instance's link-loss timer alive while no input streams. */
    fun hb(src: String, sid: String): String = srcSid("hb", src, sid)

    /** `{op:"bye", src, sid}`: this instance leaves on purpose, sent just before a deliberate close. */
    fun bye(src: String, sid: String): String = srcSid("bye", src, sid)

    /** `{op:"action", name, src, sid, args?, id?}`. */
    fun action(action: PilotAction, src: String, sid: String, id: String?): String = buildJsonObject {
        put("op", "action")
        put("name", action.name)
        put("src", src)
        put("sid", sid)
        if (action.args.isNotEmpty()) put("args", toJson(action.args))
        if (id != null) put("id", id)
    }.toString()

    private fun srcSid(op: String, src: String, sid: String) = buildJsonObject {
        put("op", op)
        put("src", src)
        put("sid", sid)
    }.toString()

    private fun srcSession(op: String, src: String, sid: String, session: Long) = buildJsonObject {
        put("op", op)
        put("src", src)
        put("sid", sid)
        put("session", session)
    }.toString()

    /** Plain values (maps, lists, strings, numbers, booleans, null, JSON) as JSON. */
    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, e) -> k.toString() to toJson(e) })
        is Iterable<*> -> JsonArray(v.map(::toJson))
        is Enum<*> -> JsonPrimitive(v.name)
        else -> JsonPrimitive(v.toString())
    }

    private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? = prim(key)?.takeIf { !it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    private fun JsonObject.float(key: String): Float? = prim(key)?.takeIf { !it.isString }?.doubleOrNull?.toFloat()

    private fun JsonObject.double(key: String): Double? = prim(key)?.takeIf { !it.isString }?.doubleOrNull

    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.takeIf { !it.isString }?.booleanOrNull

    /** A numeric array of exactly [n] entries, or null. */
    private fun floats(e: JsonElement?, n: Int): List<Float>? {
        val a = e as? JsonArray ?: return null
        if (a.size != n) return null
        return a.map { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull && !p.isString }?.doubleOrNull?.toFloat() ?: return null }
    }

    /** A numeric array of exactly [n] entries at full precision, or null. */
    private fun doubles(e: JsonElement?, n: Int): List<Double>? {
        val a = e as? JsonArray ?: return null
        if (a.size != n) return null
        return a.map { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull && !p.isString }?.doubleOrNull ?: return null }
    }
}
