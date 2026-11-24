package com.altnautica.gcs.core.demo.node

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Wire names and small helpers the demo pilot's live state, pins and frame builders share. */
internal object DemoPilotWire {
    const val IDLE = "idle"
    const val PILOT = "pilot"
    const val FC_MODE = "fc_mode"
    const val MISSION_FC = "mission_fc"
    const val SKILL = "skill"
    const val GOTO = "goto"
    const val TUNING = "tuning"

    const val SRC_ANDROID = "android"
    const val HOLD_CONTROLLER_LOST = "controller-lost"

    /** The readiness item the readiness pin fails. */
    const val READINESS_BLOCK_ID = "position"

    const val FOLLOW_SKILL_ID = "follow-me"

    /** Height below which the vehicle counts as landed, metres. */
    const val LANDED_ALT_M = 0.3

    const val ALTHOLD = 2
    const val AUTO = 3
    const val GUIDED = 4
    const val LOITER = 5
    const val RTL = 6
    const val LAND = 9
    const val POSHOLD = 16
    const val BRAKE = 17
    const val SMART_RTL = 21
    private val MODE_NAMES = mapOf(
        0 to "STABILIZE", ALTHOLD to "ALT_HOLD", AUTO to "AUTO", GUIDED to "GUIDED", LOITER to "LOITER",
        RTL to "RTL", 7 to "CIRCLE", LAND to "LAND", POSHOLD to "POSHOLD", BRAKE to "BRAKE", SMART_RTL to "SMART_RTL",
    )

    val FC_PROFILE_OK = readiness("fc_profile", true, "block", "FC failsafe parameters match the ADOS profile")

    /** The FC off the ADOS profile: blocks, with one row per parameter and the apply-profile fix. */
    val FC_PROFILE_DIFFERS = buildJsonObject {
        put("id", "fc_profile")
        put("ok", false)
        put("severity", "block")
        put("msg", "FC parameters differ from the ADOS profile: MAV_GCS_SYSID 255 (want 1), FS_GCS_ENABLE 0 (want 1), PRX1_TYPE 0 (want 2)")
        putJsonArray("params") {
            add(param("MAV_GCS_SYSID", 255.0, "1"))
            add(param("FS_GCS_ENABLE", 0.0, "1"))
            add(param("PRX1_TYPE", 0.0, "2"))
        }
        putJsonObject("fix") { put("kind", "apply_profile") }
    }

    /** Flow parameters off the sensor rules: a warning that opens the parameter editor at them. */
    val SENSOR_PARAMS_DIFFER = buildJsonObject {
        put("id", "sensor_params")
        put("ok", false)
        put("severity", "warn")
        put("msg", "Flow and rangefinder parameters differ")
        putJsonArray("params") { add(param("FLOW_TYPE", 6.0, "5 or 7")) }
        putJsonObject("fix") {
            put("kind", "parameters")
            putJsonArray("names") { add("FLOW_TYPE") }
        }
    }

    private fun param(name: String, have: Double?, want: String) = buildJsonObject {
        put("name", name)
        put("have", have)
        put("want", want)
    }

    const val BATTERY_FAILSAFE_ID = "battery_failsafe"
    val BATTERY_FAILSAFE_OK = readiness(BATTERY_FAILSAFE_ID, true, "warn", "Battery failsafe set")

    /** Both battery failsafe actions do nothing: it blocks arming in ADOS control and warns otherwise. */
    fun batteryFailsafeOff(controlMode: String) = readiness(
        BATTERY_FAILSAFE_ID, false, if (controlMode == "ados") "block" else "warn",
        "Battery failsafe does nothing: set low = SmartRTL or Land, critical = Land",
    )

    /** Without GNSS the failsafe RTL would climb to the RTL altitude: a warning whose fix sets both actions to Land. */
    val BATTERY_FAILSAFE_CONFINED_RTL = readiness(
        BATTERY_FAILSAFE_ID, false, "warn",
        "May fly without GNSS: a battery RTL climbs to the RTL altitude, into the roof indoors; set low = Land, critical = Land",
    )

    /** The pilot's own low-battery announcement (its STATUSTEXT and pill item) once it lands. */
    const val LOW_BATTERY_LAND_TEXT = "Low battery: landing"

    /** The pilot's own low-battery announcement once it returns home. */
    const val LOW_BATTERY_RTH_TEXT = "Low battery: returning home"

    fun modeName(mode: Int) = MODE_NAMES[mode] ?: "MODE $mode"

    fun ownerLabel(owner: String, profile: String) = if (owner == PILOT) "PILOT · ${profile.uppercase()}" else owner.uppercase()

    fun round1(v: Double) = Math.round(v * 10) / 10.0

    fun readiness(id: String, ok: Boolean, severity: String, msg: String) = buildJsonObject {
        put("id", id)
        put("ok", ok)
        put("severity", severity)
        put("msg", msg)
    }

    fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
}
