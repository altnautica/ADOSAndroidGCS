package com.altnautica.gcs.core.link.direct

import com.altnautica.gcs.core.link.transport.DemoTransport
import com.altnautica.gcs.core.link.transport.TcpTransport
import com.altnautica.gcs.core.link.transport.UdpTransport
import com.altnautica.gcs.core.link.transport.UsbSerialTransport
import com.altnautica.gcs.core.model.MavlinkByteTransport
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.TransportProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/**
 * Opens [Reach.DirectFc] links for the nodes [DirectLinkSource] publishes:
 * the demo vehicle, USB serial devices and manual UDP/TCP endpoints.
 */
@Singleton
class DirectFcTransportProvider @Inject constructor(
    private val usb: UsbFlightControllers,
    private val settings: DirectLinkSettings,
) : TransportProvider {

    override fun supports(reach: Reach): Boolean = reach == Reach.DirectFc

    override suspend fun open(node: NodeSummary, reach: Reach): MavlinkByteTransport {
        require(reach == Reach.DirectFc) { "Unsupported reach $reach" }
        val id = node.id.value
        return when {
            node.id == DemoNode.ID -> DemoTransport(Dispatchers.Default)
            id.startsWith("usb:") -> {
                val manager = usb.manager() ?: error("USB host is not available")
                val driver = usb.driverFor(node.id) ?: error("USB device ${node.name} is no longer attached")
                UsbSerialTransport(Dispatchers.IO, manager, driver, settings.usbBaudRate.first()).also { it.open() }
            }
            else -> when (val endpoint = DirectEndpoint.parse(id)) {
                is DirectEndpoint.Udp -> UdpTransport(Dispatchers.IO, endpoint.listenPort).also { it.open() }
                is DirectEndpoint.Tcp -> TcpTransport(Dispatchers.IO, endpoint.host, endpoint.port).also { it.open() }
                null -> error("Not a direct link node: $id")
            }
        }
    }
}
