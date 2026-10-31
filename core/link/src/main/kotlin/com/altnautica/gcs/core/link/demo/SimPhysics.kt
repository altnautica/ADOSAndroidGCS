package com.altnautica.gcs.core.link.demo

import com.altnautica.gcs.core.link.demo.ArduCopterSimulator.Companion.BATTERY_DRAIN_FACTOR
import com.altnautica.gcs.core.link.demo.ArduCopterSimulator.Companion.CIRCLE_RADIUS_M
import com.altnautica.gcs.core.link.demo.ArduCopterSimulator.Companion.CLIMB_SPEED
import com.altnautica.gcs.core.link.demo.ArduCopterSimulator.Companion.LAND_SPEED
import com.altnautica.gcs.core.link.demo.ArduCopterSimulator.Companion.MODE_AUTO
import com.altnautica.gcs.core.link.demo.ArduCopterSimulator.Companion.MODE_CIRCLE
import com.altnautica.gcs.core.link.demo.ArduCopterSimulator.Companion.MODE_GUIDED
import com.altnautica.gcs.core.link.demo.ArduCopterSimulator.Companion.MODE_LAND
import com.altnautica.gcs.core.link.demo.ArduCopterSimulator.Companion.MODE_RTL
import com.altnautica.gcs.core.link.demo.ArduCopterSimulator.Companion.TICK_MS
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.enums.MavFrame
import com.altnautica.gcs.core.mavlink.messages.MissionItemReached
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The kinematic flight model: takeoff climbs at 2.5 m/s, horizontal moves at
 * the waypoint speed, LAND descends at 1 m/s and disarms on touchdown, RTL
 * climbs to `RTL_ALT` and returns home, AUTO flies the stored mission (or a
 * 100 m circle round home when the mission is empty).
 */
internal class SimPhysics(
    private val sim: SimState,
    private val mission: SimMission,
    private val params: SimParams,
) {
    fun step(dt: Double): Unit = with(sim) {
        if (!armed) {
            // Unpowered: fall to the ground.
            if (alt > 0) alt = max(0.0, alt - 9.0 * dt)
            vn = 0.0; ve = 0.0; vd = 0.0; throttle = 0
            roll = 0.0; pitch = 0.0
            return
        }
        var desired = ZERO
        when (mode) {
            MODE_GUIDED -> {
                val toAlt = takeoffAlt
                val t = guidedTarget
                if (toAlt != null) {
                    desired = doubleArrayOf(0.0, 0.0, climbTo(toAlt))
                    if (abs(alt - toAlt) < 0.2) takeoffAlt = null
                } else if (t != null) {
                    desired = flyTo(t.north, t.east, t.alt)
                }
            }
            MODE_LAND -> desired = doubleArrayOf(0.0, 0.0, LAND_SPEED)
            MODE_RTL -> desired = rtlStep()
            MODE_AUTO -> if (!paused) desired = autoStep()
            MODE_CIRCLE -> desired = circleStep(dt).also { it[2] = 0.0 }
        }
        vn += (desired[0] - vn) * min(1.0, dt * 3)
        ve += (desired[1] - ve) * min(1.0, dt * 3)
        vd += (desired[2] - vd) * min(1.0, dt * 3)
        north += vn * dt
        east += ve * dt
        alt -= vd * dt
        if (alt <= 0.0) {
            alt = 0.0
            vd = 0.0
            if (mode == MODE_LAND || (mode == MODE_RTL && rtlPhase == 2) || landing) {
                disarm()
                return
            }
        }
        val speed = hypot(vn, ve)
        if (speed > 0.3) yaw = atan2(ve, vn)
        // Lean into the motion like a multicopter does.
        pitch = -min(0.35, speed / 20.0)
        roll = 0.03 * sin(bootMs / 900.0)
        throttle = (45 + 10 * (-vd).coerceIn(-2.0, 2.0) + speed).toInt().coerceIn(0, 100)
        batteryMah += (8.0 + speed * 0.8) * dt * 1000.0 / 3600.0 * BATTERY_DRAIN_FACTOR
    }

    fun toLocal(lat: Double, lon: Double): Pair<Double, Double> {
        val o = sim.options
        val n = (lat - o.homeLatDeg) * METERS_PER_DEG
        val e = (lon - o.homeLonDeg) * METERS_PER_DEG * cos(Math.toRadians(o.homeLatDeg))
        return n to e
    }

    fun latDeg() = sim.options.homeLatDeg + sim.north / METERS_PER_DEG

    fun lonDeg() = sim.options.homeLonDeg + sim.east / (METERS_PER_DEG * cos(Math.toRadians(sim.options.homeLatDeg)))

    private fun rtlStep(): DoubleArray = with(sim) {
        val rtlAlt = (params.value("RTL_ALT") / 100.0).coerceAtLeast(alt)
        when (rtlPhase) {
            0 -> {
                if (abs(alt - rtlAlt) < 0.3) rtlPhase = 1
                doubleArrayOf(0.0, 0.0, climbTo(rtlAlt))
            }
            1 -> {
                val v = flyTo(0.0, 0.0, alt)
                if (hypot(north, east) < 1.0) rtlPhase = 2
                doubleArrayOf(v[0], v[1], 0.0)
            }
            else -> doubleArrayOf(0.0, 0.0, LAND_SPEED)
        }
    }

    /** Velocity (m/s, NED down positive) toward [targetAlt]. */
    private fun climbTo(targetAlt: Double): Double {
        val err = targetAlt - sim.alt
        return -(err * 1.0).coerceIn(-CLIMB_SPEED, CLIMB_SPEED)
    }

    private fun flyTo(n: Double, e: Double, a: Double): DoubleArray {
        val dn = n - sim.north
        val de = e - sim.east
        val dist = hypot(dn, de)
        val speed = min(sim.waypointSpeed, dist * 0.8)
        val k = if (dist > 0.01) speed / dist else 0.0
        return doubleArrayOf(dn * k, de * k, climbTo(a))
    }

    private fun circleStep(dt: Double): DoubleArray = with(sim) {
        val omega = waypointSpeed / CIRCLE_RADIUS_M
        circleAngle += omega * dt
        val tn = CIRCLE_RADIUS_M * cos(circleAngle)
        val te = CIRCLE_RADIUS_M * sin(circleAngle)
        val v = flyTo(tn, te, max(alt, 10.0))
        // Feed-forward along the circle so it does not lag behind the target.
        v[0] += -waypointSpeed * sin(circleAngle) * 0.8
        v[1] += waypointSpeed * cos(circleAngle) * 0.8
        v
    }

    private fun autoStep(): DoubleArray = with(sim) {
        val items = mission.missionFor(SimMission.MISSION)
        if (items.size <= 1) return if (alt < 1.0) doubleArrayOf(0.0, 0.0, climbTo(10.0)) else circleStep(TICK_MS / 1000.0)
        if (currentSeq >= items.size) return ZERO
        val item = items[currentSeq]
        val itemAlt = if (item.frame == MavFrame.MAV_FRAME_GLOBAL || item.frame == MavFrame.MAV_FRAME_GLOBAL_INT) {
            item.z - options.homeAltMslM.toDouble()
        } else {
            item.z.toDouble()
        }
        val hasPosition = item.x != 0 || item.y != 0
        val (tn, te) = if (hasPosition) toLocal(item.x / 1e7, item.y / 1e7) else north to east
        when (item.command) {
            MavCmd.MAV_CMD_NAV_TAKEOFF -> {
                if (abs(alt - itemAlt) < 0.5) advance() else return doubleArrayOf(0.0, 0.0, climbTo(itemAlt))
            }
            MavCmd.MAV_CMD_NAV_WAYPOINT, MavCmd.MAV_CMD_NAV_LOITER_TIME, MavCmd.MAV_CMD_NAV_LOITER_TURNS, MavCmd.MAV_CMD_NAV_LOITER_UNLIM -> {
                val v = flyTo(tn, te, itemAlt)
                if (hypot(tn - north, te - east) < 2.0 && abs(alt - itemAlt) < 1.0) {
                    val holdS = when (item.command) {
                        MavCmd.MAV_CMD_NAV_WAYPOINT, MavCmd.MAV_CMD_NAV_LOITER_TIME -> item.param1.toDouble()
                        MavCmd.MAV_CMD_NAV_LOITER_TURNS -> item.param1 * 2 * PI * max(item.param3.toDouble(), 10.0) / waypointSpeed
                        else -> Double.POSITIVE_INFINITY
                    }
                    val until = holdUntilMs ?: (bootMs + (holdS * 1000).toLong().coerceAtLeast(0)).also { holdUntilMs = it }
                    if (bootMs >= until) advance()
                }
                return v
            }
            MavCmd.MAV_CMD_NAV_LAND -> {
                val v = flyTo(tn, te, alt)
                if (hypot(tn - north, te - east) < 1.0) {
                    landing = true
                    v[2] = LAND_SPEED
                }
                return v
            }
            MavCmd.MAV_CMD_NAV_RETURN_TO_LAUNCH -> switchMode(MODE_RTL)
            MavCmd.MAV_CMD_DO_CHANGE_SPEED -> {
                if (item.param2 > 0f) waypointSpeed = item.param2.toDouble()
                advance()
            }
            else -> advance()
        }
        ZERO
    }

    private fun advance() {
        sim.send(MissionItemReached(sim.currentSeq))
        sim.currentSeq++
        sim.holdUntilMs = null
    }

    private companion object {
        const val METERS_PER_DEG = 111_319.49
        val ZERO: DoubleArray get() = doubleArrayOf(0.0, 0.0, 0.0)
    }
}
