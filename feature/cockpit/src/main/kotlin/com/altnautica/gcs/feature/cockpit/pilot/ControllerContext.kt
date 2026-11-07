package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.PilotVehicleClass

/**
 * What the controller bindings and the light bar read about the flight. [pilot] is the
 * onboard pilot's state when one reports; the other fields fall back to the flight
 * controller's telemetry without it.
 */
data class ControllerContext(
    val pilot: PilotState? = null,
    val armed: Boolean = false,
    val landed: Boolean = true,
    val rthActive: Boolean = false,
    val vtol: Boolean = false,
    /** A VTOL hovering (Q modes or GUIDED-as-hover); a transition then goes to forward flight. */
    val hover: Boolean = false,
    val pickerOpen: Boolean = false,
    val lowBattery: Boolean = false,
    /** A failsafe is active or the vehicle link is lost. */
    val failsafe: Boolean = false,
) {
    /** The `when` conditions of `ds4.json`; `stick_l_centered` is the gesture engine's own. */
    fun holds(condition: String): Boolean = when (condition) {
        "picker_open" -> pickerOpen
        "picker_closed" -> !pickerOpen
        "on_ground" -> landed
        "airborne" -> !landed
        "rth_active" -> rthActive
        "not_rth" -> !rthActive
        "vehicle_vtol" -> vtol
        else -> false
    }

    companion object {
        private val RTH_MODES = setOf("RTL", "SMART_RTL", "QRTL")

        /** Plane GUIDED, a hover when the pilot flies a VTOL in its Q model. */
        private const val PLANE_GUIDED = 15

        /**
         * The context from the pilot state (preferred) and the flight controller's
         * [armed] flag and [modeName]. Without a pilot, a disarmed vehicle counts as on
         * the ground and an armed one as airborne.
         */
        fun of(
            pilot: PilotState?,
            armed: Boolean,
            modeName: String?,
            pickerOpen: Boolean,
            lowBattery: Boolean,
            failsafe: Boolean,
        ): ControllerContext {
            val mode = (pilot?.fcModeName ?: modeName)?.uppercase()
            val vtol = pilot?.vehicleClass == PilotVehicleClass.Vtol
            return ControllerContext(
                pilot = pilot,
                armed = pilot?.armed ?: armed,
                landed = pilot?.landed ?: !armed,
                rthActive = mode in RTH_MODES,
                vtol = vtol,
                hover = vtol && (mode?.startsWith("Q") == true || pilot?.fcMode == PLANE_GUIDED),
                pickerOpen = pickerOpen,
                lowBattery = lowBattery,
                failsafe = failsafe,
            )
        }
    }
}

/** Light bar states, in the order `ds4.json` ranks them (most urgent first). */
enum class LightbarState(val wire: String) {
    Failsafe("failsafe"),
    LowBattery("low_battery"),
    Rth("rth"),
    Mission("mission"),
    Skill("skill"),
    Pilot("pilot"),
    ArmedIdle("armed_idle"),
    Disarmed("disarmed"),
}

/** The light bar state for [c]. */
fun lightbarState(c: ControllerContext): LightbarState {
    val owner = c.pilot?.owner
    val mission = c.pilot?.mission?.state
    return when {
        c.failsafe -> LightbarState.Failsafe
        c.lowBattery -> LightbarState.LowBattery
        c.rthActive || mission == MissionState.Returning -> LightbarState.Rth
        !c.armed -> LightbarState.Disarmed
        owner == PilotOwner.MissionFc || mission == MissionState.Active -> LightbarState.Mission
        owner == PilotOwner.Skill -> LightbarState.Skill
        owner == PilotOwner.Pilot || owner == PilotOwner.Goto || owner == PilotOwner.Swarm -> LightbarState.Pilot
        else -> LightbarState.ArmedIdle
    }
}
