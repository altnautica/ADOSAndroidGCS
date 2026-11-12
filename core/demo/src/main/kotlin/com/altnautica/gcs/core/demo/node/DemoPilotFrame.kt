package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.demo.node.DemoPilotWire.FC_PROFILE_OK
import com.altnautica.gcs.core.demo.node.DemoPilotWire.FOLLOW_SKILL_ID
import com.altnautica.gcs.core.demo.node.DemoPilotWire.GOTO
import com.altnautica.gcs.core.demo.node.DemoPilotWire.HOLD_CONTROLLER_LOST
import com.altnautica.gcs.core.demo.node.DemoPilotWire.PILOT
import com.altnautica.gcs.core.demo.node.DemoPilotWire.SKILL
import com.altnautica.gcs.core.demo.node.DemoPilotWire.SRC_ANDROID
import com.altnautica.gcs.core.demo.node.DemoPilotWire.modeName
import com.altnautica.gcs.core.demo.node.DemoPilotWire.ownerLabel
import com.altnautica.gcs.core.demo.node.DemoPilotWire.readiness
import com.altnautica.gcs.core.demo.node.DemoPilotWire.round1
import com.altnautica.gcs.core.demo.node.DemoPilotWire.text
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The live input session: the surface whose sticks the demo pilot follows. */
internal data class PilotSession(val src: String, val id: Long, var lastInputMs: Long?, var axes: FloatArray = FloatArray(4))

/** The handover gate: pending until the sticks have been centred long enough. */
internal data class PilotGate(var centredSinceMs: Long? = null, var pending: Boolean = true, var progress: Float = 0f)

/** A running or just-ended launch sequence. */
internal data class PilotLaunch(
    val after: String,
    val altM: Float,
    var step: String,
    var stepMs: Long,
    var error: String? = null,
    var takeoffSentMs: Long? = null,
    var endedMs: Long? = null,
)

/** The demo pilot's own state, as the live frame reports it. */
internal class PilotLive(
    val owner: String,
    val controlMode: String,
    val profile: String,
    val avoid: String,
    val session: PilotSession?,
    val gate: PilotGate?,
    val launch: PilotLaunch?,
)

/** The flight controller's state the live frame reports. */
internal class FcLive(val armed: Boolean, val mode: Int, val landed: Boolean, val altitudeM: Double)

/** The pilot kind's live `data` at [t], before any pin is laid over it. */
internal fun liveFrame(p: PilotLive, fc: FcLive, t: Long): JsonObject {
    val s = p.session
    val age = s?.lastInputMs?.let { t - it }
    val stale = s != null && p.owner == PILOT && (age == null || age > INPUT_STALE_MS)
    return buildJsonObject {
        put("owner", p.owner)
        put("owner_label", ownerLabel(p.owner, p.profile))
        put("skill", JsonNull)
        put("vehicle_class", "multirotor")
        put("control_mode", p.controlMode)
        put("profile", p.profile)
        put("avoid", p.avoid)
        put("fc_mode", fc.mode)
        put("fc_mode_name", modeName(fc.mode))
        put("armed", fc.armed)
        put("landed", fc.landed)
        put("vtol_transitioning", false)
        put("input_src", s?.src?.let(::JsonPrimitive) ?: JsonNull)
        put("input_age_ms", age?.let(::JsonPrimitive) ?: JsonNull)
        put("hold_reason", if (stale) JsonPrimitive(HOLD_CONTROLLER_LOST) else JsonNull)
        put("avoid_state", if (p.avoid == "off") "off" else "clear")
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
        put("fc_failsafe", false)
        put("failsafe_cause", JsonNull)
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
        val g = p.gate
        if (g != null) {
            putJsonObject("gate") {
                put("pending", g.pending)
                put("progress", g.progress.toDouble())
            }
        } else {
            put("gate", JsonNull)
        }
        val l = p.launch
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
        // The pilot offers a surface's map point only in ADOS control.
        putJsonArray("surface_ops") { if (p.controlMode == "ados") add(GOTO) }
        // Height above ground follows the flight controller's height; 0 on the ground.
        put("agl_m", if (fc.landed) 0.0 else round1(fc.altitudeM))
        put("agl_source", AGL_SOURCE)
        putJsonArray("statustext") {}
    }
}

/** The view of the pilot frame's [data] the other demo feeds follow, [sincePinMs] after the current pin. */
internal fun pilotView(data: JsonObject, sincePinMs: Long): DemoPilotView {
    val skill = (data["skill"] as? JsonObject)?.text("skill_id")
    val mission = data["mission"] as? JsonObject ?: JsonObject(emptyMap())
    val active = mission.text("state") == "active"
    return DemoPilotView(
        followRunning = data.text("owner") == SKILL && skill == FOLLOW_SKILL_ID,
        mission = mission,
        missionEtaS = if (active) round1((MISSION_ETA_MS - sincePinMs % MISSION_ETA_MS) / 1000.0) else null,
        heightM = (data["agl_m"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
    )
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

private const val INPUT_STALE_MS = 300L
private const val LINK_LOSS_ACTION = "rth"
private const val AGL_SOURCE = "rangefinder"
private const val MISSION_ETA_MS = 42_000L
