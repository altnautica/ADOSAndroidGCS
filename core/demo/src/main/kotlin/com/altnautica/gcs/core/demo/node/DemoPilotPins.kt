package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.demo.DemoScenario
import com.altnautica.gcs.core.demo.node.DemoPilotWire.AUTO
import com.altnautica.gcs.core.demo.node.DemoPilotWire.FC_MODE
import com.altnautica.gcs.core.demo.node.DemoPilotWire.FC_PROFILE_OK
import com.altnautica.gcs.core.demo.node.DemoPilotWire.BATTERY_FAILSAFE_OK
import com.altnautica.gcs.core.demo.node.DemoPilotWire.batteryFailsafeOff
import com.altnautica.gcs.core.demo.node.DemoPilotWire.FOLLOW_SKILL_ID
import com.altnautica.gcs.core.demo.node.DemoPilotWire.GOTO
import com.altnautica.gcs.core.demo.node.DemoPilotWire.GUIDED
import com.altnautica.gcs.core.demo.node.DemoPilotWire.HOLD_CONTROLLER_LOST
import com.altnautica.gcs.core.demo.node.DemoPilotWire.IDLE
import com.altnautica.gcs.core.demo.node.DemoPilotWire.LAND
import com.altnautica.gcs.core.demo.node.DemoPilotWire.LOW_BATTERY_LAND_TEXT
import com.altnautica.gcs.core.demo.node.DemoPilotWire.LOW_BATTERY_RTH_TEXT
import com.altnautica.gcs.core.demo.node.DemoPilotWire.LOITER
import com.altnautica.gcs.core.demo.node.DemoPilotWire.MISSION_FC
import com.altnautica.gcs.core.demo.node.DemoPilotWire.PILOT
import com.altnautica.gcs.core.demo.node.DemoPilotWire.READINESS_BLOCK_ID
import com.altnautica.gcs.core.demo.node.DemoPilotWire.RTL
import com.altnautica.gcs.core.demo.node.DemoPilotWire.SMART_RTL
import com.altnautica.gcs.core.demo.node.DemoPilotWire.SKILL
import com.altnautica.gcs.core.demo.node.DemoPilotWire.TUNING
import com.altnautica.gcs.core.demo.node.DemoPilotWire.modeName
import com.altnautica.gcs.core.demo.node.DemoPilotWire.ownerLabel
import com.altnautica.gcs.core.demo.node.DemoPilotWire.readiness
import com.altnautica.gcs.core.demo.node.DemoPilotWire.round1
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
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
            hold != null -> holding(hold, sinceMs)
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
                    add(BATTERY_FAILSAFE_OK)
                }
            }
            DemoScenario.BatteryFailsafeOff, DemoScenario.BatteryFailsafeConfinedRtl -> {
                grounded(armed = false)
                putJsonArray("readiness") {
                    add(FC_PROFILE_OK)
                    add(readiness("position", true, "block", "Position estimate available"))
                    add(
                        if (scenario == DemoScenario.BatteryFailsafeOff) batteryFailsafeOff(controlMode)
                        else DemoPilotWire.BATTERY_FAILSAFE_CONFINED_RTL,
                    )
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
            // The tuning flight dropped below its altitude floor; the pilot holds in its place.
            DemoScenario.SetupAborted -> {
                flying(PILOT, GUIDED)
                put("setup_abort", SETUP_ABORT_KEY)
            }
            DemoScenario.ConfinedRouteFeasible -> confinedGround(routeCheck("feasible", sigmaM = 0.02, pinch = CLEAR_PINCH))
            // Unknown pose σ: the check is at best unverified, and legs 5 and 6 are not seen yet.
            DemoScenario.ConfinedRouteUnverified -> confinedGround(routeCheck("unverified", sigmaM = null, pinch = CLEAR_PINCH, unverified = listOf(5, 6)))
            // Waypoint 5 has no position: the check cannot place it, so the route stays unverified.
            DemoScenario.ConfinedRouteUnchecked -> confinedGround(routeCheck("unverified", sigmaM = 0.02, pinch = CLEAR_PINCH, unchecked = listOf(5)))
            DemoScenario.ConfinedRouteInfeasible -> confinedGround(routeCheck("infeasible", sigmaM = 0.02, pinch = TIGHT_PINCH))
            DemoScenario.ConfinedNearLimit -> {
                flying(MISSION_FC, AUTO)
                mission("active", MISSION_SEQ, MISSION_TOTAL)
                put("place_class", "confined")
                put("route_check", routeCheck("feasible", sigmaM = 0.02, pinch = CLEAR_PINCH))
                put("min_clearance_m", 1.6)
                putJsonObject("speed_cap") {
                    put("reason", "confined-near")
                    put("v_h_mps", CONFINED_CAP_MPS)
                }
            }
            DemoScenario.LinkLoss -> {
                flying(PILOT, GUIDED)
                put("link_loss_in_s", round1((LINK_LOSS_MS - sinceMs % LINK_LOSS_MS) / 1000.0))
            }
            DemoScenario.LowBatteryCountdownLand -> {
                flying(PILOT, GUIDED)
                put("low_battery_action", "land")
                put("rth_countdown_s", round1((LOW_BATTERY_MS - sinceMs % LOW_BATTERY_MS) / 1000.0))
                // A GNSS-free copter lands: the countdown banner names the landing.
                banner("pilot.banner.low_battery_land_in", "pct" to LOW_BATTERY_PCT, "need_pct" to LOW_BATTERY_NEED_PCT, "in_s" to "${LOW_BATTERY_MS / 1000}")
            }
            DemoScenario.LowBatteryLand -> lowBatteryFired("land", PILOT, LAND, "pilot.banner.low_battery_land", LOW_BATTERY_LAND_TEXT, sinceMs)
            // A multirotor's low-battery return is SmartRTL, the flight controller flying the recorded path.
            DemoScenario.LowBatteryRth ->
                lowBatteryFired("rth", FC_MODE, SMART_RTL, "pilot.banner.low_battery_smart_rtl", LOW_BATTERY_RTH_TEXT, sinceMs)
            // The pilot's own readiness ids; the lost fix itself shows on the flight controller's telemetry.
            DemoScenario.NoGnss -> putJsonArray("readiness") {
                add(FC_PROFILE_OK)
                add(readiness("position", true, "block", "Position from the EKF origin only"))
                add(BATTERY_FAILSAFE_OK)
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
    private fun JsonObjectBuilder.holding(reason: String, sinceMs: Long) {
        flying(PILOT, GUIDED)
        put("hold_reason", reason)
        when (reason) {
            HOLD_CONTROLLER_LOST -> banner("pilot.banner.controller_lost")
            // The hold has already run its link-loss action: "Link lost: rth in 0 s".
            "link-loss" -> banner("pilot.banner.link_loss", "action" to "rth", "in_s" to "0")
            "obstacle-close" -> {
                put("avoid_state", "blocked")
                put("min_clearance_m", 0.9)
                banner("pilot.banner.obstacle_close")
            }
            "blocked" -> put("avoid_state", "blocked")
            // The next leg of a confined mission is too tight: the stall flow offers skip, return or land.
            "route-infeasible" -> {
                mission("active", TIGHT_LEG_TO - 1, MISSION_TOTAL)
                put("place_class", "confined")
                put("route_check", routeCheck("infeasible", sigmaM = 0.02, pinch = TIGHT_PINCH))
                banner("pilot.banner.route_infeasible", "leg" to "${TIGHT_LEG_TO - 1}→$TIGHT_LEG_TO", "clearance_m" to "0.52", "required_m" to "0.58")
                putJsonObject("mission_stall") {
                    put("kind", "mission")
                    put("seq", TIGHT_LEG_TO - 1)
                    put("wp_label", "${TIGHT_LEG_TO - 1}")
                    put("since_ms", JsonNull)
                    put("skip_in_s", round1((STALL_SKIP_MS - sinceMs % STALL_SKIP_MS) / 1000.0))
                    put("window_s", STALL_WINDOW_S)
                }
            }
        }
    }

    /** On the ground and disarmed in a confined place with the demo mission loaded, the route check [rc] laid on. */
    private fun JsonObjectBuilder.confinedGround(rc: JsonObject) {
        grounded(armed = false)
        mission("loaded", 1, MISSION_TOTAL)
        put("place_class", "confined")
        put("route_check", rc)
    }

    /** A route check [state] with the demo airframe's margin; [pinch] is `(from, to, clearance)`. */
    private fun routeCheck(state: String, sigmaM: Double?, pinch: Triple<Int, Int, Double>, unverified: List<Int> = emptyList(), unchecked: List<Int> = emptyList()) = buildJsonObject {
        put("state", state)
        put("required_m", REQUIRED_M)
        put("margin_m", MARGIN_M)
        put("sigma_m", sigmaM?.let(::JsonPrimitive) ?: JsonNull)
        putJsonObject("pinch") {
            put("from_seq", pinch.first)
            put("to_seq", pinch.second)
            putJsonArray("ned") {
                add(41.25)
                add(-17.5)
                add(-2.0)
            }
            put("clearance_m", pinch.third)
            put("required_m", REQUIRED_M)
        }
        putJsonArray("unverified_legs") { unverified.forEach { add(it) } }
        if (unchecked.isNotEmpty()) putJsonArray("unchecked") { unchecked.forEach { add(it) } }
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
        // The pilot offers `goto` only in ADOS control.
        putJsonArray("surface_ops") { if (code != "control_mode") add(GOTO) }
    }

    /** The flight controller in RTL on its own failsafe. */
    private fun JsonObjectBuilder.fcFailsafeRtl() {
        flying(FC_MODE, RTL)
        put("fc_failsafe", true)
        put("failsafe_cause", "fc")
        banner("pilot.banner.fc_mode_change", "mode" to modeName(RTL), "cause" to "fc")
    }

    /**
     * The pilot's low-battery countdown ran out: [owner] flies [action] in flight mode [mode],
     * the pilot shows [bannerKey] and announces [text] as a CRITICAL STATUSTEXT.
     */
    private fun JsonObjectBuilder.lowBatteryFired(action: String, owner: String, mode: Int, bannerKey: String, text: String, sinceMs: Long) {
        flying(owner, mode)
        put("low_battery_action", action)
        banner(bannerKey, "pct" to LOW_BATTERY_PCT)
        putJsonArray("statustext") {
            add(buildJsonObject {
                put("severity", SEVERITY_CRITICAL)
                put("text", text)
                put("age_ms", sinceMs % STATUS_TEXT_WRAP_MS)
            })
        }
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

        /** True when [scenario] stands for an armed vehicle in the air. */
        fun airborne(scenario: DemoScenario?): Boolean {
            if (scenario == null) return false
            val f = DemoPilotPins("ados", "normal", gotoAccepted = false).fields(scenario, 0)
            return (f["landed"] as? JsonPrimitive)?.booleanOrNull == false
        }

        /** The flight mode [scenario] reports, or null when it leaves the flight controller's own. */
        fun fcMode(scenario: DemoScenario?): Int? {
            if (scenario == null) return null
            val f = DemoPilotPins("ados", "normal", gotoAccepted = false).fields(scenario, 0)
            return (f["fc_mode"] as? JsonPrimitive)?.intOrNull
        }

        /** Height a flying pin reports, metres. */
        const val PIN_HEIGHT_M = 15.0

        /** The setup-strings key the setup-abort pin reports. */
        const val SETUP_ABORT_KEY = "setup.blocker.aborted_altitude_floor"
        private const val LINK_LOSS_MS = 10_000L
        private const val LOW_BATTERY_MS = 10_000L
        private const val LOW_BATTERY_PCT = "18"
        private const val LOW_BATTERY_NEED_PCT = "20"
        private const val MISSION_SEQ = 4
        private const val MISSION_TOTAL = 6

        /** The demo airframe's confined margin (prop-tip radius 0.377 m + 0.1 m) and the clearance it needs with σ 0.02 m. */
        private const val MARGIN_M = 0.477
        private const val REQUIRED_M = 0.577
        private const val CONFINED_CAP_MPS = 0.5

        /** The too-tight leg ends at this waypoint; its clearance is under [REQUIRED_M]. */
        private const val TIGHT_LEG_TO = 4
        private val TIGHT_PINCH = Triple(TIGHT_LEG_TO - 1, TIGHT_LEG_TO, 0.52)
        private val CLEAR_PINCH = Triple(2, 3, 0.91)
        private const val STALL_SKIP_MS = 45_000L
        private const val STALL_WINDOW_S = 15.0
        private const val DEMO_MISSION_ID = 1
        private const val FOLLOW_PLUGIN_ID = "com.altnautica.follow-me"
        private const val FOLLOW_LABEL = "Follow-Me"

        /** MAV_SEVERITY_WARNING. */
        private const val SEVERITY_WARNING = 4

        /** MAV_SEVERITY_CRITICAL, the pilot's low-battery announcement. */
        private const val SEVERITY_CRITICAL = 2
        // Every text stays younger than the cockpit's 10 s status-text hold, so all nine stay listed.
        private const val STATUS_TEXT_SPACING_MS = 500L
        private const val STATUS_TEXT_WRAP_MS = 1_000L

        /**
         * Nine warnings the pill lists side by side, one of them long. None is a PreArm text:
         * the pill keeps only the newest PreArm, so those would collapse into one item.
         */
        private val STATUS_TEXTS = listOf(
            "Vibration compensation ON",
            "EKF variance",
            "GPS glitch",
            "Compass variance",
            "Battery 1 low",
            "Radio failsafe cleared",
            "High wind speed",
            "Terrain data missing",
            "Gyro inconsistent: the second inertial unit disagrees with the first by more than its limit; land and recalibrate before the next flight",
        )
    }
}
