package com.altnautica.gcs.core.mavlink.modes

import com.altnautica.gcs.core.mavlink.enums.MavAutopilot
import com.altnautica.gcs.core.mavlink.enums.MavType
import com.altnautica.gcs.core.model.FlightMode

/** Firmware family resolved from a vehicle's HEARTBEAT. */
enum class FirmwareFamily { ArduCopter, ArduPlane, ArduRover, ArduSub, Px4, Unknown }

/**
 * custom_mode ↔ mode name tables for ArduPilot (Copter, Plane, Rover, Sub)
 * and PX4 (main mode in bits 16-23, sub mode in bits 24-31).
 */
object FlightModeTables {

    private val COPTER = listOf(
        0 to "STABILIZE", 1 to "ACRO", 2 to "ALT_HOLD", 3 to "AUTO", 4 to "GUIDED", 5 to "LOITER", 6 to "RTL",
        7 to "CIRCLE", 9 to "LAND", 11 to "DRIFT", 13 to "SPORT", 14 to "FLIP", 15 to "AUTOTUNE", 16 to "POSHOLD",
        17 to "BRAKE", 18 to "THROW", 19 to "AVOID_ADSB", 20 to "GUIDED_NOGPS", 21 to "SMART_RTL", 22 to "FLOWHOLD",
        23 to "FOLLOW", 24 to "ZIGZAG", 25 to "SYSTEMID", 26 to "HELI_AUTOROTATE", 27 to "AUTO_RTL", 28 to "TURTLE",
    )

    private val PLANE = listOf(
        0 to "MANUAL", 1 to "CIRCLE", 2 to "STABILIZE", 3 to "TRAINING", 4 to "ACRO", 5 to "FBWA", 6 to "FBWB",
        7 to "CRUISE", 8 to "AUTOTUNE", 10 to "AUTO", 11 to "RTL", 12 to "LOITER", 13 to "TAKEOFF", 14 to "AVOID_ADSB",
        15 to "GUIDED", 17 to "QSTABILIZE", 18 to "QHOVER", 19 to "QLOITER", 20 to "QLAND", 21 to "QRTL",
        22 to "QAUTOTUNE", 23 to "QACRO", 24 to "THERMAL", 25 to "LOITER_TO_QLAND", 26 to "AUTOLAND",
    )

    private val ROVER = listOf(
        0 to "MANUAL", 1 to "ACRO", 3 to "STEERING", 4 to "HOLD", 5 to "LOITER", 6 to "FOLLOW", 7 to "SIMPLE",
        8 to "DOCK", 9 to "CIRCLE", 10 to "AUTO", 11 to "RTL", 12 to "SMART_RTL", 15 to "GUIDED",
    )

    private val SUB = listOf(
        0 to "STABILIZE", 1 to "ACRO", 2 to "ALT_HOLD", 3 to "AUTO", 4 to "GUIDED", 7 to "CIRCLE", 9 to "SURFACE",
        16 to "POSHOLD", 19 to "MANUAL", 20 to "MOTOR_DETECT", 21 to "SURFTRAK",
    )

    /** PX4 main mode ids. */
    object Px4Main {
        const val MANUAL = 1
        const val ALTCTL = 2
        const val POSCTL = 3
        const val AUTO = 4
        const val ACRO = 5
        const val OFFBOARD = 6
        const val STABILIZED = 7
        const val RATTITUDE = 8
        const val TERMINATION = 10
        const val ALTITUDE_CRUISE = 11
    }

    /** PX4 AUTO sub modes. Sub mode 7 is reserved and never commanded. */
    object Px4AutoSub {
        const val READY = 1
        const val TAKEOFF = 2
        const val LOITER = 3
        const val MISSION = 4
        const val RTL = 5
        const val LAND = 6
        const val FOLLOW_TARGET = 8
        const val PRECLAND = 9
        const val VTOL_TAKEOFF = 10
        const val DESCEND = 20
    }

    private val PX4 = listOf(
        Triple(Px4Main.MANUAL, 0, "MANUAL"),
        Triple(Px4Main.ALTCTL, 0, "ALT_HOLD"),
        Triple(Px4Main.POSCTL, 0, "POSHOLD"),
        Triple(Px4Main.POSCTL, 1, "ORBIT"),
        Triple(Px4Main.STABILIZED, 0, "STABILIZE"),
        Triple(Px4Main.ACRO, 0, "ACRO"),
        Triple(Px4Main.OFFBOARD, 0, "OFFBOARD"),
        Triple(Px4Main.RATTITUDE, 0, "RATTITUDE"),
        Triple(Px4Main.AUTO, Px4AutoSub.MISSION, "AUTO"),
        Triple(Px4Main.AUTO, Px4AutoSub.LOITER, "LOITER"),
        Triple(Px4Main.AUTO, Px4AutoSub.RTL, "RTL"),
        Triple(Px4Main.AUTO, Px4AutoSub.LAND, "LAND"),
        Triple(Px4Main.AUTO, Px4AutoSub.TAKEOFF, "TAKEOFF"),
        Triple(Px4Main.AUTO, Px4AutoSub.FOLLOW_TARGET, "FOLLOW_ME"),
        Triple(Px4Main.AUTO, Px4AutoSub.READY, "READY"),
        Triple(Px4Main.AUTO, Px4AutoSub.PRECLAND, "PRECLAND"),
        Triple(Px4Main.AUTO, Px4AutoSub.VTOL_TAKEOFF, "VTOL_TAKEOFF"),
        Triple(Px4Main.ALTITUDE_CRUISE, 0, "ALTITUDE_CRUISE"),
        Triple(Px4Main.POSCTL, 2, "POSITION_SLOW"),
    )

    /** PX4 states that are reported but can never be requested. */
    private val PX4_REPORTED_ONLY = listOf(
        Triple(Px4Main.AUTO, Px4AutoSub.DESCEND, "DESCEND"),
        Triple(Px4Main.TERMINATION, 0, "TERMINATION"),
    )

    fun px4CustomMode(main: Int, sub: Int): Long = ((main.toLong() and 0xFF) shl 16) or ((sub.toLong() and 0xFF) shl 24)

    fun family(autopilot: Int, vehicleType: Int): FirmwareFamily = when (autopilot) {
        MavAutopilot.MAV_AUTOPILOT_PX4 -> FirmwareFamily.Px4
        MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA -> when (vehicleType) {
            MavType.MAV_TYPE_FIXED_WING,
            MavType.MAV_TYPE_VTOL_TAILSITTER_DUOROTOR,
            MavType.MAV_TYPE_VTOL_TAILSITTER_QUADROTOR,
            MavType.MAV_TYPE_VTOL_TILTROTOR,
            MavType.MAV_TYPE_VTOL_FIXEDROTOR,
            MavType.MAV_TYPE_VTOL_TAILSITTER,
            MavType.MAV_TYPE_VTOL_TILTWING,
            MavType.MAV_TYPE_VTOL_RESERVED5,
            -> FirmwareFamily.ArduPlane
            MavType.MAV_TYPE_GROUND_ROVER, MavType.MAV_TYPE_SURFACE_BOAT -> FirmwareFamily.ArduRover
            MavType.MAV_TYPE_SUBMARINE -> FirmwareFamily.ArduSub
            else -> FirmwareFamily.ArduCopter
        }
        else -> FirmwareFamily.Unknown
    }

    /** Modes an operator can request on this firmware, in table order. */
    fun available(family: FirmwareFamily): List<FlightMode> = when (family) {
        FirmwareFamily.ArduCopter -> COPTER.map { FlightMode(it.first.toLong(), it.second) }
        FirmwareFamily.ArduPlane -> PLANE.map { FlightMode(it.first.toLong(), it.second) }
        FirmwareFamily.ArduRover -> ROVER.map { FlightMode(it.first.toLong(), it.second) }
        FirmwareFamily.ArduSub -> SUB.map { FlightMode(it.first.toLong(), it.second) }
        FirmwareFamily.Px4 -> PX4.map { FlightMode(px4CustomMode(it.first, it.second), it.third) }
        FirmwareFamily.Unknown -> emptyList()
    }

    /** Names a reported custom_mode; unknown values read `MODE_<n>`. */
    fun decode(family: FirmwareFamily, customMode: Long): FlightMode {
        val name = when (family) {
            FirmwareFamily.ArduCopter -> COPTER.firstOrNull { it.first.toLong() == customMode }?.second
            FirmwareFamily.ArduPlane -> PLANE.firstOrNull { it.first.toLong() == customMode }?.second
            FirmwareFamily.ArduRover -> ROVER.firstOrNull { it.first.toLong() == customMode }?.second
            FirmwareFamily.ArduSub -> SUB.firstOrNull { it.first.toLong() == customMode }?.second
            FirmwareFamily.Px4 -> {
                val main = ((customMode shr 16) and 0xFF).toInt()
                val sub = ((customMode shr 24) and 0xFF).toInt()
                (PX4 + PX4_REPORTED_ONLY).firstOrNull { it.first == main && it.second == sub }?.third
                    // Main modes without sub modes report an arbitrary sub byte.
                    ?: (PX4 + PX4_REPORTED_ONLY).firstOrNull { it.first == main && it.second == 0 && main != Px4Main.AUTO }?.third
            }
            FirmwareFamily.Unknown -> null
        }
        return FlightMode(customMode, name ?: "MODE_$customMode")
    }

    /** Looks up a requestable mode by name (case-insensitive). */
    fun byName(family: FirmwareFamily, name: String): FlightMode? =
        available(family).firstOrNull { it.name.equals(name, ignoreCase = true) }
}
