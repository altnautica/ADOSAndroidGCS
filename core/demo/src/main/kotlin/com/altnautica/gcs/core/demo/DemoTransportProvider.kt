package com.altnautica.gcs.core.demo

import com.altnautica.gcs.core.link.transport.DemoTransport
import com.altnautica.gcs.core.model.MavlinkByteTransport
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.TransportProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

/** Opens the in-process simulated vehicle for the demo drone. */
@Singleton
class DemoTransportProvider @Inject constructor() : TransportProvider {

    override fun supports(node: NodeSummary, reach: Reach): Boolean =
        reach == Reach.DirectFc && node.id == DemoIds.DRONE

    override suspend fun open(node: NodeSummary, reach: Reach): MavlinkByteTransport {
        require(supports(node, reach)) { "Not the demo vehicle: ${node.id.value}" }
        return DemoTransport(Dispatchers.Default)
    }
}
