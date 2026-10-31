package com.altnautica.gcs.feature.node.common

import com.altnautica.gcs.core.agent.capture.CaptureApi
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.agent.sensors.SensorsApi
import com.altnautica.gcs.core.link.direct.DemoNode
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.feature.node.demo.DemoCaptureApi
import com.altnautica.gcs.feature.node.demo.DemoGroundSensors
import com.altnautica.gcs.feature.node.demo.DemoVisorSensors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * The capture and sensor APIs for a node: an in-process [LocalNodeApis] that
 * handles it, else its agent's. Null when the node has no agent link this app
 * can use.
 */
@Singleton
class NodeApis @Inject constructor(
    private val nodes: NodeRepository,
    private val clients: NodeClients,
    private val locals: Set<@JvmSuppressWildcards LocalNodeApis>,
) {
    private val demoCapture by lazy { DemoCaptureApi() }
    private val demoVisorSensors by lazy { DemoVisorSensors() }
    private val demoGroundSensors by lazy { DemoGroundSensors() }

    /** The in-process provider serving [id], or null when its agent does. */
    fun local(id: DeviceId): LocalNodeApis? = locals.firstOrNull { it.handles(id) }

    suspend fun capture(id: DeviceId): CaptureApi? = local(id)?.let { return it.capture(id) }
        ?: if (id == DemoNode.VISOR_ID) demoCapture else client(id)?.capture

    suspend fun sensors(id: DeviceId): SensorsApi? = local(id)?.let { return it.sensors(id) } ?: when (id) {
        DemoNode.VISOR_ID -> demoVisorSensors
        DemoNode.GROUND_ID -> demoGroundSensors
        else -> client(id)?.sensors
    }

    private suspend fun client(id: DeviceId) = clients.clientFor(nodes.node(id).filterNotNull().first())
}
