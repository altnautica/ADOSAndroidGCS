package com.altnautica.gcs.data.mavlink

import android.util.Log
import com.altnautica.gcs.data.telemetry.AttitudeState
import com.altnautica.gcs.data.telemetry.BatteryState
import com.altnautica.gcs.data.telemetry.CommandAckState
import com.altnautica.gcs.data.telemetry.EkfState
import com.altnautica.gcs.data.telemetry.FlightMode
import com.altnautica.gcs.data.telemetry.GpsState
import com.altnautica.gcs.data.telemetry.ImuState
import com.altnautica.gcs.data.telemetry.NavControllerState
import com.altnautica.gcs.data.telemetry.ParamValueState
import com.altnautica.gcs.data.telemetry.PositionState
import com.altnautica.gcs.data.telemetry.RcChannelsRawState
import com.altnautica.gcs.data.telemetry.RcChannelsState
import com.altnautica.gcs.data.telemetry.ServoOutputState
import com.altnautica.gcs.data.telemetry.SysStatusState
import com.altnautica.gcs.data.telemetry.TelemetryStore
import com.altnautica.gcs.data.telemetry.VfrState
import com.altnautica.gcs.data.telemetry.VibrationState
import com.altnautica.gcs.data.telemetry.WindState
import io.dronefleet.mavlink.MavlinkMessage
import io.dronefleet.mavlink.ardupilotmega.EkfStatusReport
import io.dronefleet.mavlink.ardupilotmega.Wind
import io.dronefleet.mavlink.common.Attitude
import io.dronefleet.mavlink.common.BatteryStatus
import io.dronefleet.mavlink.common.CommandAck
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.GpsRawInt
import io.dronefleet.mavlink.common.HomePosition
import io.dronefleet.mavlink.common.MissionCurrent
import io.dronefleet.mavlink.common.MissionItemReached
import io.dronefleet.mavlink.common.NavControllerOutput
import io.dronefleet.mavlink.common.ParamValue
import io.dronefleet.mavlink.common.RcChannels
import io.dronefleet.mavlink.common.RcChannelsRaw
import io.dronefleet.mavlink.common.ScaledImu
import io.dronefleet.mavlink.common.ServoOutputRaw
import io.dronefleet.mavlink.common.Statustext
import io.dronefleet.mavlink.common.SysStatus
import io.dronefleet.mavlink.common.VfrHud
import io.dronefleet.mavlink.common.Vibration
import io.dronefleet.mavlink.minimal.Heartbeat
import io.dronefleet.mavlink.minimal.MavModeFlag
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MavLinkParser @Inject constructor(
    private val telemetryStore: TelemetryStore
) {

    companion object {
        private const val TAG = "MavLinkParser"
    }

    // Optional callback for CommandAck routing to CommandQueue
    var onCommandAck: ((commandId: Int, result: Int) -> Unit)? = null

    // Optional callback for ParamValue routing to ParameterManager
    var onParamValue: ((name: String, value: Float, type: Int, count: Int, index: Int) -> Unit)? = null

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
                is HomePosition -> handleHomePosition(payload)
                is Statustext -> handleStatusText(payload)
                is ParamValue -> handleParamValue(payload)
                is CommandAck -> handleCommandAck(payload)
                is RcChannelsRaw -> handleRcChannelsRaw(payload)
                is MissionCurrent -> handleMissionCurrent(payload)
                is MissionItemReached -> handleMissionItemReached(payload)
                is NavControllerOutput -> handleNavController(payload)
                is ScaledImu -> handleScaledImu(payload)
                is ServoOutputRaw -> handleServoOutput(payload)
                is Vibration -> handleVibration(payload)
                is EkfStatusReport -> handleEkfStatus(payload)
                is RcChannels -> handleRcChannels(payload)
                is Wind -> handleWind(payload)
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

    private fun handleHomePosition(home: HomePosition) {
        telemetryStore.updateHomePosition(
            PositionState(
                lat = home.latitude() / 1e7,
                lon = home.longitude() / 1e7,
                altMsl = home.altitude() / 1000f,
                altRel = 0f,
                heading = 0,
            )
        )
    }

    private fun handleStatusText(statusText: Statustext) {
        val text = "[${statusText.severity().value()}] ${statusText.text()}"
        telemetryStore.addStatusMessage(text)
    }

    // --- New decoders below ---

    private fun handleParamValue(param: ParamValue) {
        val name = param.paramId()
        val value = param.paramValue()
        val type = param.paramType().value()
        val count = param.paramCount()
        val index = param.paramIndex()

        telemetryStore.updateParamValue(
            ParamValueState(
                name = name,
                value = value,
                type = type,
                index = index,
                count = count
            )
        )

        // Route to ParameterManager if registered
        onParamValue?.invoke(name, value, type, count, index)
    }

    private fun handleCommandAck(ack: CommandAck) {
        val commandId = ack.command().value()
        val result = ack.result().value()

        telemetryStore.updateCommandAck(
            CommandAckState(
                commandId = commandId,
                result = result
            )
        )

        // Route to CommandQueue if registered
        onCommandAck?.invoke(commandId, result)
    }

    private fun handleRcChannelsRaw(rc: RcChannelsRaw) {
        telemetryStore.updateRcChannelsRaw(
            RcChannelsRawState(
                chan1 = rc.chan1Raw(),
                chan2 = rc.chan2Raw(),
                chan3 = rc.chan3Raw(),
                chan4 = rc.chan4Raw(),
                chan5 = rc.chan5Raw(),
                chan6 = rc.chan6Raw(),
                chan7 = rc.chan7Raw(),
                chan8 = rc.chan8Raw(),
                rssi = rc.rssi()
            )
        )
    }

    private fun handleMissionCurrent(mission: MissionCurrent) {
        telemetryStore.updateMissionCurrent(mission.seq())
    }

    private fun handleMissionItemReached(mission: MissionItemReached) {
        telemetryStore.updateMissionItemReached(mission.seq())
    }

    private fun handleNavController(nav: NavControllerOutput) {
        telemetryStore.updateNavController(
            NavControllerState(
                navRoll = nav.navRoll(),
                navPitch = nav.navPitch(),
                navBearing = nav.navBearing(),
                targetBearing = nav.targetBearing(),
                wpDist = nav.wpDist(),
                altError = nav.altError(),
                xtrackError = nav.xtrackError()
            )
        )
    }

    private fun handleScaledImu(imu: ScaledImu) {
        telemetryStore.updateImu(
            ImuState(
                xacc = imu.xacc(),
                yacc = imu.yacc(),
                zacc = imu.zacc(),
                xgyro = imu.xgyro(),
                ygyro = imu.ygyro(),
                zgyro = imu.zgyro(),
                xmag = imu.xmag(),
                ymag = imu.ymag(),
                zmag = imu.zmag()
            )
        )
    }

    private fun handleServoOutput(servo: ServoOutputRaw) {
        telemetryStore.updateServoOutput(
            ServoOutputState(
                servo1 = servo.servo1Raw(),
                servo2 = servo.servo2Raw(),
                servo3 = servo.servo3Raw(),
                servo4 = servo.servo4Raw(),
                servo5 = servo.servo5Raw(),
                servo6 = servo.servo6Raw(),
                servo7 = servo.servo7Raw(),
                servo8 = servo.servo8Raw()
            )
        )
    }

    private fun handleVibration(vib: Vibration) {
        telemetryStore.updateVibration(
            VibrationState(
                vibrationX = vib.vibrationX(),
                vibrationY = vib.vibrationY(),
                vibrationZ = vib.vibrationZ(),
                clipping0 = vib.clipping0().toLong(),
                clipping1 = vib.clipping1().toLong(),
                clipping2 = vib.clipping2().toLong()
            )
        )
    }

    private fun handleEkfStatus(ekf: EkfStatusReport) {
        telemetryStore.updateEkf(
            EkfState(
                velocityVariance = ekf.velocityVariance(),
                posHorizVariance = ekf.posHorizVariance(),
                posVertVariance = ekf.posVertVariance(),
                compassVariance = ekf.compassVariance(),
                terrainAltVariance = ekf.terrainAltVariance(),
                flags = ekf.flags().value()
            )
        )
    }

    private fun handleRcChannels(rc: RcChannels) {
        val channels = listOf(
            rc.chan1Raw(), rc.chan2Raw(), rc.chan3Raw(), rc.chan4Raw(),
            rc.chan5Raw(), rc.chan6Raw(), rc.chan7Raw(), rc.chan8Raw(),
            rc.chan9Raw(), rc.chan10Raw(), rc.chan11Raw(), rc.chan12Raw(),
            rc.chan13Raw(), rc.chan14Raw(), rc.chan15Raw(), rc.chan16Raw(),
            rc.chan17Raw(), rc.chan18Raw()
        )
        telemetryStore.updateRcChannels(
            RcChannelsState(
                chancount = rc.chancount(),
                channels = channels,
                rssi = rc.rssi()
            )
        )
    }

    private fun handleWind(wind: Wind) {
        telemetryStore.updateWind(
            WindState(
                direction = wind.direction(),
                speed = wind.speed(),
                speedZ = wind.speedZ()
            )
        )
    }
}
