package com.altnautica.gcs.core.link.demo

import com.altnautica.gcs.core.link.demo.ArduCopterSimulator.Companion.BATTERY_CAPACITY_MAH
import com.altnautica.gcs.core.mavlink.enums.MavAutopilot
import com.altnautica.gcs.core.mavlink.enums.MavBatteryFunction
import com.altnautica.gcs.core.mavlink.enums.MavBatteryType
import com.altnautica.gcs.core.mavlink.enums.MavModeFlag
import com.altnautica.gcs.core.mavlink.enums.MavState
import com.altnautica.gcs.core.mavlink.enums.MavType
import com.altnautica.gcs.core.mavlink.messages.Attitude
import com.altnautica.gcs.core.mavlink.messages.BatteryStatus
import com.altnautica.gcs.core.mavlink.messages.EkfStatusReport
import com.altnautica.gcs.core.mavlink.messages.GlobalPositionInt
import com.altnautica.gcs.core.mavlink.messages.GpsRawInt
import com.altnautica.gcs.core.mavlink.messages.Heartbeat
import com.altnautica.gcs.core.mavlink.messages.HomePosition
import com.altnautica.gcs.core.mavlink.messages.MissionCurrent
import com.altnautica.gcs.core.mavlink.messages.RcChannels
import com.altnautica.gcs.core.mavlink.messages.SysStatus
import com.altnautica.gcs.core.mavlink.messages.VfrHud
import com.altnautica.gcs.core.mavlink.messages.Vibration
import kotlin.math.hypot

/** Outbound telemetry built from the current [SimState]. */
internal class SimTelemetry(
    private val sim: SimState,
    private val physics: SimPhysics,
    private val mission: SimMission,
) {
    private val headingDeg: Double get() = (Math.toDegrees(sim.yaw) + 360) % 360
    private val groundSpeed: Double get() = hypot(sim.vn, sim.ve)

    fun heartbeat() = Heartbeat(
        type = MavType.MAV_TYPE_QUADROTOR,
        autopilot = MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA,
        baseMode = MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED or (if (sim.armed) MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED else 0),
        customMode = sim.mode.toLong(),
        systemStatus = if (sim.armed) MavState.MAV_STATE_ACTIVE else MavState.MAV_STATE_STANDBY,
        mavlinkVersion = 3,
    )

    fun attitude() = Attitude(
        timeBootMs = sim.bootMs,
        roll = sim.roll.toFloat(),
        pitch = sim.pitch.toFloat(),
        yaw = sim.yaw.toFloat(),
    )

    fun globalPosition() = GlobalPositionInt(
        timeBootMs = sim.bootMs,
        lat = (physics.latDeg() * 1e7).toInt(),
        lon = (physics.lonDeg() * 1e7).toInt(),
        alt = ((sim.options.homeAltMslM + sim.alt) * 1000).toInt(),
        relativeAlt = (sim.alt * 1000).toInt(),
        vx = (sim.vn * 100).toInt(),
        vy = (sim.ve * 100).toInt(),
        vz = (sim.vd * 100).toInt(),
        hdg = (headingDeg * 100).toInt(),
    )

    fun vfrHud() = VfrHud(
        airspeed = groundSpeed.toFloat(),
        groundspeed = groundSpeed.toFloat(),
        heading = headingDeg.toInt(),
        throttle = sim.throttle,
        alt = (sim.options.homeAltMslM + sim.alt).toFloat(),
        climb = (-sim.vd).toFloat(),
    )

    fun gpsRaw() = GpsRawInt(
        timeUsec = sim.bootMs * 1000,
        fixType = 3,
        lat = (physics.latDeg() * 1e7).toInt(),
        lon = (physics.lonDeg() * 1e7).toInt(),
        alt = ((sim.options.homeAltMslM + sim.alt) * 1000).toInt(),
        eph = 80,
        epv = 120,
        vel = (groundSpeed * 100).toInt(),
        cog = (headingDeg * 100).toInt(),
        satellitesVisible = 14,
    )

    fun sysStatus() = SysStatus(
        onboardControlSensorsPresent = SENSORS,
        onboardControlSensorsEnabled = SENSORS,
        onboardControlSensorsHealth = SENSORS,
        load = 250,
        voltageBattery = (voltage() * 1000).toInt(),
        currentBattery = (currentA() * 100).toInt(),
        batteryRemaining = remainingPct(),
    )

    fun batteryStatus(): BatteryStatus {
        val cell = (voltage() / 4 * 1000).toInt()
        return BatteryStatus(
            id = 0,
            batteryFunction = MavBatteryFunction.MAV_BATTERY_FUNCTION_ALL,
            type = MavBatteryType.MAV_BATTERY_TYPE_LIPO,
            temperature = 3100,
            voltages = IntArray(10) { if (it < 4) cell else 0xFFFF },
            currentBattery = (currentA() * 100).toInt(),
            currentConsumed = sim.batteryMah.toInt(),
            energyConsumed = -1,
            batteryRemaining = remainingPct(),
            timeRemaining = 0,
        )
    }

    // A healthy EKF: every solution flag except CONST_POS_MODE (128), which means dead reckoning.
    fun ekfStatus() = EkfStatusReport(flags = 0x37F, velocityVariance = 0.05f, posHorizVariance = 0.04f, posVertVariance = 0.03f, compassVariance = 0.02f)

    fun vibration() = Vibration(timeUsec = sim.bootMs * 1000, vibrationX = 3.1f, vibrationY = 2.8f, vibrationZ = 4.2f)

    fun home() = HomePosition(
        latitude = (sim.options.homeLatDeg * 1e7).toInt(),
        longitude = (sim.options.homeLonDeg * 1e7).toInt(),
        altitude = (sim.options.homeAltMslM * 1000).toInt(),
        q = floatArrayOf(1f, 0f, 0f, 0f),
    )

    fun missionCurrent() = MissionCurrent(
        seq = sim.currentSeq,
        total = mission.missionFor(SimMission.MISSION).size,
        missionState = if (sim.paused) 4 else 3,
    )

    fun rcChannels() = RcChannels(
        timeBootMs = sim.bootMs,
        chancount = 8,
        chan1Raw = 1500, chan2Raw = 1500, chan3Raw = if (sim.armed) 1500 else 1000, chan4Raw = 1500,
        chan5Raw = 1100, chan6Raw = 1500, chan7Raw = 1000, chan8Raw = 1000,
        chan9Raw = 0xFFFF, chan10Raw = 0xFFFF, chan11Raw = 0xFFFF, chan12Raw = 0xFFFF, chan13Raw = 0xFFFF,
        chan14Raw = 0xFFFF, chan15Raw = 0xFFFF, chan16Raw = 0xFFFF, chan17Raw = 0xFFFF, chan18Raw = 0xFFFF,
        rssi = 230,
    )

    private fun remainingPct(): Int = (100 - sim.batteryMah / BATTERY_CAPACITY_MAH * 100).toInt().coerceIn(0, 100)

    private fun voltage(): Double = 12.8 + 4.0 * remainingPct() / 100.0

    private fun currentA(): Double = if (sim.armed) 8.0 + groundSpeed * 0.8 else 0.4

    private companion object {
        /** Gyro, accel, mag, baro, GPS, RC, motor outputs, AHRS, battery present/enabled/healthy. */
        const val SENSORS: Long = 0x0221802FL
    }
}
