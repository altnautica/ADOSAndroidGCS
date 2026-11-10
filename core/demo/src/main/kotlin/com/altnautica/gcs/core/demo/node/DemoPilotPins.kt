package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.demo.DemoScenario
import com.altnautica.gcs.core.demo.node.DemoPilotWire.AUTO
import com.altnautica.gcs.core.demo.node.DemoPilotWire.FC_MODE
import com.altnautica.gcs.core.demo.node.DemoPilotWire.FC_PROFILE_OK
import com.altnautica.gcs.core.demo.node.DemoPilotWire.FOLLOW_SKILL_ID
import com.altnautica.gcs.core.demo.node.DemoPilotWire.GOTO
import com.altnautica.gcs.core.demo.node.DemoPilotWire.GUIDED
import com.altnautica.gcs.core.demo.node.DemoPilotWire.HOLD_CONTROLLER_LOST
import com.altnautica.gcs.core.demo.node.DemoPilotWire.IDLE
import com.altnautica.gcs.core.demo.node.DemoPilotWire.LOITER
import com.altnautica.gcs.core.demo.node.DemoPilotWire.MISSION_FC
import com.altnautica.gcs.core.demo.node.DemoPilotWire.PILOT
import com.altnautica.gcs.core.demo.node.DemoPilotWire.READINESS_BLOCK_ID
import com.altnautica.gcs.core.demo.node.DemoPilotWire.RTL
import com.altnautica.gcs.core.demo.node.DemoPilotWire.SKILL
import com.altnautica.gcs.core.demo.node.DemoPilotWire.TUNING
import com.altnautica.gcs.core.demo.node.DemoPilotWire.modeName
import com.altnautica.gcs.core.demo.node.DemoPilotWire.ownerLabel
import com.altnautica.gcs.core.demo.node.DemoPilotWire.readiness
import com.altnautica.gcs.core.demo.node.DemoPilotWire.round1
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The fields a pinned [DemoScenario] lays over the demo pilot's live state. A pin that puts
 * `armed`, `landed` or the flight mode in its fields is also the state the demo pilot's
 * actions answer from while it is pinned.
 */
internal class DemoPilotPins(
    /** The pilot's control mode (`ados`, `fc_manual`, `elrs`). */
    private val controlMode: String,
    /** The pilot's flight profile, for the PILOT owner label. */
    private val profile: String,
    /** A surface's `goto` was accepted under [DemoScenario.FlyHere] and is still shown. */
    private val gotoAccepted: Boolean,
) {
    /** The fields [scenario] lays over the live state, [sinceMs] after it was pinned. */
    fun fields(scenario: DemoScenario, sinceMs: Long): JsonObject = buildJsonObject {
        val hold = scenario.holdReason
        val refusal = scenario.gotoRefusal
        when {
            hold != null -> holding(hold)
            refusal != null -> refusing(refusal)
            else -> state(scenario, sinceMs)
        }
    }

    /** The state a pin other than a hold or a goto refusal stands for. */
    private fun JsonObjectBuilder.state(scenario: DemoScenario, sinceMs: Long) {
        when (scenario) {
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
            DemoScenario.FcFailsafeRtl -> fcFailsafeRtl()
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
                if (gotoAccepted) flying(GOTO, GUIDED) else flying(PILOT, GUIDED)
                // The pilot flies a surface's point only in ADOS control; otherwise the surface uses the FC.
                putJsonArray("surface_ops") { if (controlMode == "ados") add(GOTO) }
            }
            else -> Unit
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
                put("owner_label", ownerLabel(IDLE, profile))
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
            "failsafe" -> fcFailsafeRtl()
            else -> flying(PILOT, GUIDED)
        }
        putJsonArray("surface_ops") { add(GOTO) }
    }

    /** The flight controller in RTL on its own failsafe. */
    private fun JsonObjectBuilder.fcFailsafeRtl() {
        flying(FC_MODE, RTL)
        put("fc_failsafe", true)
        put("failsafe_cause", "fc")
        banner("pilot.banner.fc_mode_change", "mode" to modeName(RTL), "cause" to "fc")
    }

    private fun JsonObjectBuilder.flying(owner: String, mode: Int, label: String = ownerLabel(owner, profile)) {
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
        put("owner_label", ownerLabel(IDLE, profile))
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

    companion object {
        /** True while [scenario] holds a flight-controller failsafe: the pilot refuses `pause`. */
        fun failsafe(scenario: DemoScenario?): Boolean =
            scenario == DemoScenario.FcFailsafeRtl || scenario?.gotoRefusal == "failsafe"

        /** Height a flying pin reports, metres. */
        private const val PIN_HEIGHT_M = 15.0
        private const val LINK_LOSS_MS = 10_000L
        private const val MISSION_SEQ = 4
        private const val MISSION_TOTAL = 6
        private const val DEMO_MISSION_ID = 1
        private const val FOLLOW_PLUGIN_ID = "com.altnautica.follow-me"
        private const val FOLLOW_LABEL = "Follow-Me"

        /** MAV_SEVERITY_WARNING. */
        private const val SEVERITY_WARNING = 4
        private const val STATUS_TEXT_SPACING_MS = 1_500L
        private const val STATUS_TEXT_WRAP_MS = 20_000L
        private val STATUS_TEXTS = listOf(
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
    }
}
