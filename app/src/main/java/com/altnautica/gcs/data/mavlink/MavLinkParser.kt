package com.altnautica.gcs.data.mavlink

import android.util.Log
import com.altnautica.gcs.data.telemetry.AttitudeState
import com.altnautica.gcs.data.telemetry.BatteryState
import com.altnautica.gcs.data.telemetry.FlightMode
import com.altnautica.gcs.data.telemetry.GpsState
import com.altnautica.gcs.data.telemetry.PositionState
import com.altnautica.gcs.data.telemetry.SysStatusState
import com.altnautica.gcs.data.telemetry.TelemetryStore
import com.altnautica.gcs.data.telemetry.VfrState
import io.dronefleet.mavlink.common.Attitude
import io.dronefleet.mavlink.common.BatteryStatus
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.GpsRawInt
import io.dronefleet.mavlink.minimal.Heartbeat
import io.dronefleet.mavlink.minimal.MavModeFlag
import io.dronefleet.mavlink.common.Statustext
import io.dronefleet.mavlink.common.SysStatus
import io.dronefleet.mavlink.common.VfrHud
import io.dronefleet.mavlink.MavlinkMessage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MavLinkParser @Inject constructor(
    private val telemetryStore: TelemetryStore
) {

    companion object {
        private const val TAG = "MavLinkParser"
    }

    fun handleMessage(mavMessage: MavlinkMessage<*>) {
        val payload = mavMessage.payload
        try {
            when (payload) {
                is Heartbeat -> handleHeartbeat(payload)
                is Attitude -> handleAttitude(payload)
                is GlobalPositionInt -> handlePosition(payload)
                is VfrHud -> handleVfr(payload)
                is SysStatus -> handleSysStatus(payload)
                is GpsRawInt -> handleGps(payload)
                is BatteryStatus -> handleBattery(payload)
                is Statustext -> handleStatusText(payload)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle message: ${payload::class.simpleName}", e)
        }
    }

    private fun handleHeartbeat(heartbeat: Heartbeat) {
        // Extract flight mode from custom_mode for ArduPilot copter
        val mode = FlightMode.fromNumber(heartbeat.customMode().toInt())
        telemetryStore.updateFlightMode(mode)

        // Armed state from base_mode flag
        val armed = heartbeat.baseMode().flagsEnabled(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED)
        telemetryStore.updateArmed(armed)
    }

    private fun handleAttitude(attitude: Attitude) {
        telemetryStore.updateAttitude(
            AttitudeState(
                roll = Math.toDegrees(attitude.roll().toDouble()).toFloat(),
                pitch = Math.toDegrees(attitude.pitch().toDouble()).toFloat(),
                yaw = Math.toDegrees(attitude.yaw().toDouble()).toFloat()
            )
        )
    }

    private fun handlePosition(pos: GlobalPositionInt) {
        telemetryStore.updatePosition(
            PositionState(
                lat = pos.lat() / 1e7,
                lon = pos.lon() / 1e7,
                altMsl = pos.alt() / 1000f,
                altRel = pos.relativeAlt() / 1000f,
                heading = pos.hdg() / 100
            )
        )
    }

    private fun handleVfr(vfr: VfrHud) {
        telemetryStore.updateVfr(
            VfrState(
                airspeed = vfr.airspeed(),
                groundspeed = vfr.groundspeed(),
                heading = vfr.heading(),
                throttle = vfr.throttle(),
                alt = vfr.alt(),
                climb = vfr.climb()
            )
        )
    }

    private fun handleSysStatus(sys: SysStatus) {
        telemetryStore.updateSysStatus(
            SysStatusState(
                sensorsPresent = sys.onboardControlSensorsPresent().value().toLong(),
                sensorsEnabled = sys.onboardControlSensorsEnabled().value().toLong(),
                sensorsHealth = sys.onboardControlSensorsHealth().value().toLong(),
                load = sys.load(),
                voltageBattery = sys.voltageBattery(),
                currentBattery = sys.currentBattery(),
                batteryRemaining = sys.batteryRemaining()
            )
        )
    }

    private fun handleGps(gps: GpsRawInt) {
        telemetryStore.updateGps(
            GpsState(
                fixType = gps.fixType().value(),
                satellites = gps.satellitesVisible(),
                hdop = gps.eph(),
                lat = gps.lat() / 1e7,
                lon = gps.lon() / 1e7,
                alt = gps.alt() / 1000f
            )
        )
    }

    private fun handleBattery(battery: BatteryStatus) {
        val voltages = battery.voltages()
        val totalVoltage = if (voltages.isNotEmpty()) {
            voltages.filter { it != 0 && it != 65535 }.sum() / 1000f
        } else {
            0f
        }
        telemetryStore.updateBattery(
            BatteryState(
                voltage = totalVoltage,
                current = battery.currentBattery() / 100f,
                remaining = battery.batteryRemaining()
            )
        )
    }

    private fun handleStatusText(statusText: Statustext) {
        val text = "[${statusText.severity().value()}] ${statusText.text()}"
        telemetryStore.addStatusMessage(text)
    }
}
