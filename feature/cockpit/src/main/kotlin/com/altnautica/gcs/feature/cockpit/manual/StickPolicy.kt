package com.altnautica.gcs.feature.cockpit.manual

import com.altnautica.gcs.core.skills.builtin.VehicleClass

/**
 * Which flight modes stick control may fly in. A self-centring throttle stick only
 * means "hold altitude" in a mode that holds altitude, so by default the sticks are
 * limited to the assisted modes of each airframe class; a per-vehicle opt-in allows
 * every mode.
 */
object StickPolicy {
    val ASSISTED: Map<VehicleClass, Set<String>> = mapOf(
        VehicleClass.Multirotor to setOf("ALT_HOLD", "LOITER", "POSHOLD", "FLOWHOLD"),
        VehicleClass.Vtol to setOf("QHOVER", "QLOITER", "FBWB", "CRUISE"),
        VehicleClass.FixedWing to setOf("FBWB", "CRUISE"),
        VehicleClass.Rover to setOf("STEERING"),
        VehicleClass.Sub to setOf("ALT_HOLD", "POSHOLD", "SURFTRAK"),
        VehicleClass.Unknown to setOf("ALT_HOLD", "LOITER", "POSHOLD"),
    )

    fun isAssisted(mode: String?, cls: VehicleClass): Boolean =
        mode != null && mode.uppercase() in ASSISTED.getValue(cls)

    fun allowed(mode: String?, cls: VehicleClass, allModes: Boolean): Boolean =
        allModes || isAssisted(mode, cls)
}
