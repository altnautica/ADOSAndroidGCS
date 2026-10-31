package com.altnautica.gcs.core.demo

import com.altnautica.gcs.core.model.DeviceId

/** The simulated nodes shown while demo mode is on. */
object DemoIds {
    /** Simulated ArduCopter vehicle, flown over an in-process MAVLink link. */
    val DRONE: DeviceId = DeviceId("demo-copter")
    const val DRONE_NAME: String = "Demo drone"

    /** Handheld capture rig; its pages are served from demo data, never an agent. */
    val VISOR: DeviceId = DeviceId("demo-visor")
    const val VISOR_NAME: String = "Demo Visor"

    /** Ground station with GNSS and LoRa demo data. */
    val GROUND: DeviceId = DeviceId("demo-ground")
    const val GROUND_NAME: String = "Demo ground station"

    val ALL: Set<DeviceId> = setOf(DRONE, VISOR, GROUND)
}
