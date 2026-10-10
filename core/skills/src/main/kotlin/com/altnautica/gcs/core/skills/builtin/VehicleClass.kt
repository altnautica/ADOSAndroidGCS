package com.altnautica.gcs.core.skills.builtin

import com.altnautica.gcs.core.model.SkillContext

/** Airframe class that decides which firmware mode a [ModePreset] means. */
enum class VehicleClass { Multirotor, FixedWing, Vtol, Rover, Sub, Unknown }

// MAV_TYPE values from the MAVLink common definitions.
private const val MAV_TYPE_FIXED_WING = 1
private const val MAV_TYPE_SUBMARINE = 12
private val MULTIROTOR_TYPES = setOf(
    2, // QUADROTOR
    3, // COAXIAL
    4, // HELICOPTER
    13, // HEXAROTOR
    14, // OCTOROTOR
    15, // TRICOPTER
    29, // DODECAROTOR
    35, // DECAROTOR
)
private val VTOL_TYPES = setOf(19, 20, 21, 22, 23, 24, 25) // VTOL_TAILSITTER_DUOROTOR .. VTOL_RESERVED5
private val ROVER_TYPES = setOf(10, 11) // GROUND_ROVER, SURFACE_BOAT

/**
 * QuadPlane modes flown as a multicopter. A QuadPlane reports `MAV_TYPE_FIXED_WING`
 * by default, so the current mode is what tells a hovering VTOL from a wing in flight.
 */
private val HOVER_MODES = setOf("QSTABILIZE", "QHOVER", "QLOITER", "QLAND", "QRTL", "QAUTOTUNE", "QACRO", "LOITER_TO_QLAND")

/** Airframe class from a HEARTBEAT `type` and the mode the vehicle is in now. */
fun vehicleClass(mavType: Int?, currentMode: String?): VehicleClass = when {
    mavType == null -> VehicleClass.Unknown
    mavType in VTOL_TYPES -> VehicleClass.Vtol
    mavType == MAV_TYPE_FIXED_WING -> if (currentMode?.uppercase() in HOVER_MODES) VehicleClass.Vtol else VehicleClass.FixedWing
    mavType in MULTIROTOR_TYPES -> VehicleClass.Multirotor
    mavType in ROVER_TYPES -> VehicleClass.Rover
    mavType == MAV_TYPE_SUBMARINE -> VehicleClass.Sub
    else -> VehicleClass.Unknown
}

/** Airframe class of the vehicle in [this] context; [VehicleClass.Unknown] before the first heartbeat. */
fun SkillContext.vehicleClass(): VehicleClass =
    vehicleClass(vehicle?.telemetry?.heartbeat?.value?.vehicleType, mode?.name)
