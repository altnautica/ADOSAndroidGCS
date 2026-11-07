package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.agent.capture.CaptureApi
import com.altnautica.gcs.core.agent.fcsetup.SetupBackend
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.agent.sensors.SensorsApi
import com.altnautica.gcs.core.demo.DemoIds
import com.altnautica.gcs.core.demo.DemoVehicleHub
import com.altnautica.gcs.core.demo.video.DemoVideoFactory
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.VideoSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Serves the demo Visor's capture and sensor pages, the demo ground station's sensors, and
 * the demo drone's autonomy feeds and flight-controller setup engine.
 */
@Singleton
class DemoNodeApis @Inject constructor(private val hub: DemoVehicleHub) : LocalNodeApis {
    private val capture by lazy { DemoCaptureApi() }
    private val visorSensors by lazy { DemoVisorSensors() }
    private val groundSensors by lazy { DemoGroundSensors() }
    private val autonomy by lazy { DemoAutonomy(hub) }
    private val fcSetup by lazy { DemoFcSetup(CoroutineScope(SupervisorJob() + Dispatchers.Default)) }

    override fun handles(id: DeviceId): Boolean = id == DemoIds.VISOR || id == DemoIds.GROUND

    override fun capture(id: DeviceId): CaptureApi? = if (id == DemoIds.VISOR) capture else null

    override fun sensors(id: DeviceId): SensorsApi? = when (id) {
        DemoIds.VISOR -> visorSensors
        DemoIds.GROUND -> groundSensors
        else -> null
    }

    /** A labelled test pattern per camera. */
    override fun capturePreview(id: DeviceId, camera: String?): VideoSource? =
        if (id == DemoIds.VISOR) DemoVideoFactory.source(id, camera) else null

    /** Scripted detections, navigation stream and live map for the demo drone. */
    override fun autonomy(id: DeviceId): AutonomyFeedApi? = if (id == DemoIds.DRONE) autonomy else null

    /** The setup wizards of the demo drone's flight controller. */
    override fun fcSetup(id: DeviceId): SetupBackend? = if (id == DemoIds.DRONE) fcSetup else null
}
