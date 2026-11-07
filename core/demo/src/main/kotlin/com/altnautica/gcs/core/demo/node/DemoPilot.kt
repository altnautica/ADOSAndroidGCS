package com.altnautica.gcs.core.demo.node

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The demo drone's onboard pilot: it answers the pilot channel's client frames the way
 * the vehicle does (stick input makes the owner `pilot` with this surface as the input
 * source; actions arm, take off, land, return, pause, kill and change profile, control
 * mode and avoidance) and publishes the resulting `pilot` state, which also rides the
 * demo navigation stream.
 */
internal class DemoPilot(private val now: () -> Long) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private var owner = "idle"
    private var armed = false
    private var landed = true
    private var fcMode = LOITER
    private var controlMode = "ados"
    private var profile = "normal"
    private var avoid = "brake"
    private var inputSrc: String? = null
    private var lastInputMs: Long? = null
    private var sessionLive = false

    /** Pilot frames at 10 Hz plus an `action_result` for each action in [outgoing]. */
    fun stream(outgoing: Flow<String>): Flow<String> = channelFlow {
        launch { outgoing.collect { text -> handle(text)?.let { send(it) } } }
        while (true) {
            send(frame())
            delay(TICK_MS)
        }
    }

    /** One `{kind:"pilot", ts_ms, data}` frame of the current state. */
    fun frame(): String {
        val t = now()
        return buildJsonObject {
            put("kind", "pilot")
            put("ts_ms", t)
            put("data", state(t))
        }.toString()
    }

    /** Applies one client frame; returns the `action_result` frame for an action. */
    fun handle(text: String): String? {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val src = o.text("src")
        synchronized(lock) {
            when (o.text("op")) {
                "input" -> {
                    sessionLive = true
                    inputSrc = src
                    lastInputMs = now()
                    if (armed && !landed && owner in STICK_TAKES) owner = "pilot"
                    if (owner == "pilot") fcMode = GUIDED
                }
                "release" -> {
                    sessionLive = false
                    lastInputMs = null
                }
                "session.take" -> {
                    sessionLive = true
                    inputSrc = src
                }
                "action" -> {
                    val name = o.text("name") ?: return null
                    val args = o["args"] as? JsonObject
                    val err = act(name, args)
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

    /** Runs [name]; null on success, otherwise the refusal reason. */
    private fun act(name: String, args: JsonObject?): String? {
        when {
            name.startsWith("profile:") -> profile = name.removePrefix("profile:").takeIf { it in PROFILES } ?: return "unknown_profile"
            name.startsWith("control_mode:") -> controlMode = name.removePrefix("control_mode:").takeIf { it in CONTROL_MODES } ?: return "unknown_mode"
            name.startsWith("avoid:") -> avoid = name.removePrefix("avoid:").takeIf { it in AVOID_MODES } ?: return "unknown_avoid"
            name.startsWith("vtol:") -> return "vehicle_class"
            else -> when (name) {
                "arm" -> {
                    if (armed) return "armed"
                    armed = true
                    landed = true
                }
                "disarm" -> {
                    if (!landed) return "airborne"
                    disarm()
                }
                "takeoff", "launch" -> {
                    if (armed && !landed) return "airborne"
                    armed = true
                    landed = false
                    owner = "pilot"
                    fcMode = GUIDED
                }
                "land" -> {
                    if (!armed) return "disarmed"
                    disarm()
                }
                "rth" -> {
                    if (!armed) return "disarmed"
                    owner = "fc_mode"
                    fcMode = RTL
                }
                "pause", "take_control" -> {
                    if (!armed) return "disarmed"
                    owner = "pilot"
                    fcMode = GUIDED
                }
                "hand_to_fc" -> {
                    if (!armed) return "disarmed"
                    owner = "fc_mode"
                    fcMode = when (args?.text("mode")) {
                        "poshold" -> POSHOLD
                        "althold" -> ALTHOLD
                        else -> LOITER
                    }
                    controlMode = "fc_manual"
                }
                "kill" -> disarm()
                "resume", "rth_cancel" -> Unit
                else -> return "unknown_action"
            }
        }
        return null
    }

    private fun disarm() {
        armed = false
        landed = true
        owner = "idle"
        fcMode = LOITER
    }

    private fun state(t: Long): JsonObject = synchronized(lock) {
        val age = lastInputMs?.let { t - it }
        val stale = sessionLive && owner == "pilot" && (age == null || age > INPUT_STALE_MS)
        buildJsonObject {
            put("owner", owner)
            put("owner_label", if (owner == "pilot") "PILOT · ${profile.uppercase()}" else owner.uppercase())
            put("skill", JsonNull)
            put("vehicle_class", "multirotor")
            put("control_mode", controlMode)
            put("profile", profile)
            put("avoid", avoid)
            put("fc_mode", fcMode)
            put("fc_mode_name", MODE_NAMES.getValue(fcMode))
            put("armed", armed)
            put("landed", landed)
            put("input_src", inputSrc?.let(::JsonPrimitive) ?: JsonNull)
            put("input_age_ms", age?.let(::JsonPrimitive) ?: JsonNull)
            put("hold_reason", if (stale) JsonPrimitive("input-stale") else JsonNull)
            put("avoid_state", if (avoid == "off") "off" else "clear")
            put("min_clearance_m", 3.2)
            put("goal_ned", JsonNull)
            putJsonArray("plan_ned") {}
            put("setpoint_ned", JsonNull)
            putJsonArray("readiness") {
                add(readiness("fc_profile", true, "block", "FC failsafe parameters match the ADOS profile"))
                add(readiness("position", true, "block", "Position estimate available"))
            }
            put("link_loss_in_s", JsonNull)
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
            if (sessionLive && inputSrc != null) {
                putJsonObject("input_session") {
                    put("src", inputSrc)
                    put("label", if (inputSrc == SRC_ANDROID) "Android" else inputSrc)
                }
            } else {
                put("input_session", JsonNull)
            }
            put("gate", JsonNull)
            put("sequence", JsonNull)
            put("banner", JsonNull)
            put("rth_countdown_s", JsonNull)
        }
    }

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
        const val SRC_ANDROID = "android"
        const val GUIDED = 4
        const val LOITER = 5
        const val RTL = 6
        const val ALTHOLD = 2
        const val POSHOLD = 16
        val MODE_NAMES = mapOf(GUIDED to "GUIDED", LOITER to "LOITER", RTL to "RTL", ALTHOLD to "ALT_HOLD", POSHOLD to "POSHOLD")
        val STICK_TAKES = setOf("idle", "pilot")
        val PROFILES = setOf("cine", "normal", "sport")
        val CONTROL_MODES = setOf("ados", "fc_manual", "elrs")
        val AVOID_MODES = setOf("brake", "bypass", "off")
    }
}
