package com.altnautica.gcs.feature.node.common

import com.altnautica.gcs.core.agent.capture.CaptureApi
import com.altnautica.gcs.core.agent.sensors.SensorsApi
import com.altnautica.gcs.core.link.direct.DemoNode
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.fleet.data.NodeRepository
import com.altnautica.gcs.feature.node.demo.DemoCaptureApi
import com.altnautica.gcs.feature.node.demo.DemoGroundSensors
import com.altnautica.gcs.feature.node.demo.DemoVisorSensors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * The capture and sensor APIs for a node: its agent's, or the demo data for
 * the demo nodes. Null when the node has no agent link this app can use.
 */
@Singleton
class NodeApis @Inject constructor(
    private val nodes: NodeRepository,
    private val clients: NodeClients,
) {
    private val demoCapture by lazy { DemoCaptureApi() }
    private val demoVisorSensors by lazy { DemoVisorSensors() }
    private val demoGroundSensors by lazy { DemoGroundSensors() }

    suspend fun capture(id: DeviceId): CaptureApi? =
        if (id == DemoNode.VISOR_ID) demoCapture else client(id)?.capture

    suspend fun sensors(id: DeviceId): SensorsApi? = when (id) {
        DemoNode.VISOR_ID -> demoVisorSensors
        DemoNode.GROUND_ID -> demoGroundSensors
        else -> client(id)?.sensors
    }

    private suspend fun client(id: DeviceId) = clients.clientFor(nodes.node(id).filterNotNull().first())
}
