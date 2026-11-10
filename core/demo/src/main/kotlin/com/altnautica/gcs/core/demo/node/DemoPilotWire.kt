package com.altnautica.gcs.core.demo.node

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

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
    private val MODE_NAMES = mapOf(
        0 to "STABILIZE", ALTHOLD to "ALT_HOLD", AUTO to "AUTO", GUIDED to "GUIDED", LOITER to "LOITER",
        RTL to "RTL", 7 to "CIRCLE", LAND to "LAND", POSHOLD to "POSHOLD", BRAKE to "BRAKE",
    )

    val FC_PROFILE_OK = readiness("fc_profile", true, "block", "FC failsafe parameters match the ADOS profile")

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
