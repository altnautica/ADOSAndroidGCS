package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.demo.DemoFc
import com.altnautica.gcs.core.demo.DemoScenario
import com.altnautica.gcs.core.demo.node.DemoPilotWire.GUIDED
import com.altnautica.gcs.core.demo.node.DemoPilotWire.LAND
import com.altnautica.gcs.core.demo.node.DemoPilotWire.LANDED_ALT_M
import com.altnautica.gcs.core.demo.node.DemoPilotWire.RTL
import com.altnautica.gcs.core.mavlink.enums.MavCmd

/**
 * The flight controller follows the pin: a flying pin takes it into the air and then into
 * the pin's flight mode, so its own telemetry agrees with the pilot frame; it lands again
 * when the pin is lifted for one that is not flying. [DemoScenario.NoGnss] drops its fix.
 */
internal class DemoPinFlight(private val fc: DemoFc, private val setMode: (Int) -> Unit) {
    /** True while the flight controller is in the air because a flying pin put it there. */
    private var pinLifted = false

    /** The mode the flying pin puts the flight controller in, and whether it waits for the climb to end. */
    var pinMode: Int? = null
        private set
    var pinModePending = false

    /** Follows [scenario]; true when the flight controller takes off for it, which ends a running launch. */
    fun followPin(scenario: DemoScenario?): Boolean {
        val fix = scenario != DemoScenario.NoGnss
        if (fc.gnssFix != fix) fc.gnssFix = fix
        pinModePending = false
        if (DemoPilotPins.airborne(scenario)) {
            // An RTL would bring the flight controller home and land it, ending the pin.
            pinMode = DemoPilotPins.fcMode(scenario)?.takeIf { it != RTL }
            if (fc.armed && !landed()) {
                // Still coming down from an earlier pin: this pin's flight lands again when lifted.
                if (fc.mode == LAND) pinLifted = true
                holdPinMode()
                return false
            }
            setMode(GUIDED)
            fc.command(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, 1f)
            fc.command(MavCmd.MAV_CMD_NAV_TAKEOFF, p7 = DemoPilotPins.PIN_HEIGHT_M.toFloat())
            pinLifted = true
            pinModePending = true
            return true
        }
        pinMode = null
        if (pinLifted) {
            pinLifted = false
            if (fc.armed) fc.command(MavCmd.MAV_CMD_NAV_LAND)
        }
        return false
    }

    /** Puts the airborne flight controller in the pin's mode; one still landing for an earlier pin holds in GUIDED. */
    fun holdPinMode() {
        val mode = pinMode ?: if (fc.mode == LAND) GUIDED else return
        if (fc.mode != mode) setMode(mode)
    }

    private fun landed(): Boolean = fc.altitudeM < LANDED_ALT_M
}
