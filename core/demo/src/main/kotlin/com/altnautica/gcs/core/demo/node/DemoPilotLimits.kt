package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.demo.node.DemoPilotWire.ALTHOLD
import com.altnautica.gcs.core.demo.node.DemoPilotWire.GUIDED
import com.altnautica.gcs.core.demo.node.DemoPilotWire.LOITER
import com.altnautica.gcs.core.demo.node.DemoPilotWire.POSHOLD
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

// The demo pilot's timings, limits and accepted argument values ([DemoPilot]).

internal const val TICK_MS = 100L
internal const val SESSION_LIVE_MS = 1_000L
internal const val GATE_MS = 500L
internal const val GATE_CENTRED = 0.1f
internal const val PREEMPT_AXIS = 0.3f
internal const val STEP_TIMEOUT_MS = 3_000L
internal const val TAKEOFF_TIMEOUT_MS = 60_000L
internal const val RESEND_MS = 1_000L
internal const val MODE_SETTLE_MS = 1_500L
internal const val SEQUENCE_SHOWN_MS = 5_000L
internal const val GOTO_SHOWN_MS = 20_000L
internal const val ARRIVED_FRACTION = 0.9f
internal const val TAKEOFF_ALT_M = 1.5f
internal const val LAUNCH_ALT_M = 3f
internal const val MAX_ALT_M = 120f
internal const val FORCE_DISARM = 21196f

/** ArduCopter modes the flight controller arms in. */
internal val ARMABLE_MODES = setOf(0, 1, ALTHOLD, GUIDED, LOITER, 13, POSHOLD)
internal val AFTERS = setOf("hold", "skill", "mission", "goto")
internal val PROFILES = setOf("cine", "normal", "sport")
internal val CONTROL_MODES = setOf("ados", "fc_manual", "elrs")
internal val AVOID_MODES = setOf("brake", "bypass", "off")

/** Actions refused while the flight controller flies its own failsafe (pause and goto check it themselves). */
internal val FAILSAFE_REFUSED = setOf("take_control", "resume", "takeoff", "launch")

/** One `{kind:"pilot", ts_ms, data}` frame. */
internal fun frameOf(t: Long, data: JsonObject): String = buildJsonObject {
    put("kind", "pilot")
    put("ts_ms", t)
    put("data", data)
}.toString()

/** A positive, finite `alt_m` argument, or null. */
internal fun altArg(args: JsonObject?): Float? =
    (args?.get("alt_m") as? JsonPrimitive)?.doubleOrNull?.toFloat()?.takeIf { it.isFinite() && it > 0f }
