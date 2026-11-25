package com.altnautica.gcs.core.model

import kotlinx.coroutines.flow.StateFlow

/**
 * One stick frame for the onboard pilot. Axes are in [-1, 1] after the surface's mode
 * mapping and deadzone: roll + right, pitch + forward, throttle + climb, yaw + clockwise.
 * [buttons] is the controller button bitfield (W3C standard index → bit).
 */
data class PilotInput(val roll: Float, val pitch: Float, val throttle: Float, val yaw: Float, val buttons: Long)

/** A named pilot action with its optional arguments (`launch`, `hand_to_fc`). */
data class PilotAction(val name: String, val args: Map<String, Any?> = emptyMap()) {
    companion object {
        const val PAUSE = "pause"
        const val RESUME = "resume"
        const val TAKE_CONTROL = "take_control"
        const val HAND_TO_FC = "hand_to_fc"
        const val LAUNCH = "launch"
        const val TAKEOFF = "takeoff"
        const val LAND = "land"
        const val RTH = "rth"
        const val ARM = "arm"
        const val DISARM = "disarm"
        const val KILL = "kill"

        /** Cancels a low-battery return countdown without braking (pause also cancels it and brakes). */
        const val RTH_CANCEL = "rth_cancel"

        /** Flies to a map point: `{lat, lon, alt_m}`, altitude above home. Offered when `surface_ops` lists it. */
        const val GOTO = "goto"

        /** Skips the stalled waypoint now (the mission continues with the next item). */
        const val MISSION_SKIP = "mission_skip"

        fun profile(p: PilotProfile) = PilotAction("profile:${p.wire}")
        fun controlMode(m: ControlMode) = PilotAction("control_mode:${m.wire}")
        fun avoid(m: AvoidMode) = PilotAction("avoid:${m.wire}")

        /** VTOL transition: to forward flight when [toFixedWing], else to hover. */
        fun vtol(toFixedWing: Boolean) = PilotAction(if (toFixedWing) "vtol:fw" else "vtol:mc")

        /** The place class (refused while armed); the pilot keeps it across restarts. */
        fun placeClass(c: PlaceClass) = PilotAction("place_class:${c.wire}")

        /** Clears the connected flight controller's learned airframe record after a rebalance. */
        const val AIRFRAME_RESET = "airframe_reset"

        /** The launch after-actions a take-off sends: hold where it climbed, or fly the loaded mission. */
        const val AFTER_HOLD = "hold"
        const val AFTER_MISSION = "mission"

        /** The supervised launch: spool, level lift-off, climb to [altM], then [after]. */
        fun launch(after: String, altM: Float) = PilotAction(LAUNCH, mapOf("after" to after, "alt_m" to altM))
    }
}

/** The pilot's answer to one action sent on the [PilotLink]. */
sealed interface PilotActionResult {
    data object Accepted : PilotActionResult

    /** The pilot refused the action; [reason] is its `err` text when it gave one. */
    data class Refused(val reason: String?) : PilotActionResult

    /** Sent, but no `action_result` arrived within the answer window. */
    data object NoAnswer : PilotActionResult

    /** Not sent: the channel is down. */
    data object Unavailable : PilotActionResult
}

/**
 * The live pilot channel of one vehicle (`/api/v1/pilot/stream`). Input frames are
 * dropped while [connected] is false; an action waits for the pilot's `action_result`.
 */
interface PilotLink {
    /** Name this surface reports as its input source. */
    val src: String

    val connected: StateFlow<Boolean>

    /** Latest pilot state pushed on this link, or null before the first. */
    val state: StateFlow<PilotState?>

    /**
     * True while a pilot state arrived within [PilotState.STALE_MS]. Channel keepalives
     * keep [connected] up but say nothing about whether the pilot itself answers.
     */
    val stateFresh: Boolean

    fun input(frame: PilotInput)

    /** Ends this surface's stick session; the pilot brakes to a hold. */
    fun release()

    /** Sends [action] and suspends until the pilot answers it or the answer window ends. */
    suspend fun action(action: PilotAction): PilotActionResult

    /** Takes the stick session over from another surface. */
    fun takeSession(): Boolean
}
