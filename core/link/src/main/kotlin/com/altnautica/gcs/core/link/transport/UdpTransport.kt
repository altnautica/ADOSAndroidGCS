package com.altnautica.gcs.core.link.transport

import com.altnautica.gcs.core.model.TransportState
import com.altnautica.gcs.core.model.WriteResult
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Listens on [listenPort] (14550 by default) and replies to whoever sent the
 * newest datagram, the way autopilots and telemetry radios stream to a GCS.
 */
class UdpTransport(
    context: CoroutineContext,
    private val listenPort: Int,
) : ChannelTransport(context) {

    private var socket: DatagramSocket? = null

    @Volatile
    private var peer: SocketAddress? = null

    fun open() {
        mutableState.value = TransportState.Connecting
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(listenPort))
            }
        } catch (e: Exception) {
            finish(TransportState.Failed(e.message ?: "UDP bind failed"))
            return
        }
        socket = s
        mutableState.value = TransportState.Open
        scope.launch(Dispatchers.IO) {
            val buf = ByteArray(MAX_DATAGRAM)
            val packet = DatagramPacket(buf, buf.size)
            while (isActive) {
                try {
                    s.receive(packet)
                } catch (e: Exception) {
                    if (isActive) finish(TransportState.Closed(e.message))
                    break
                }
                peer = packet.socketAddress
                deliver(buf.copyOf(packet.length))
                packet.length = buf.size
            }
        }
    }

    override suspend fun write(bytes: ByteArray): WriteResult {
        val s = socket ?: return WriteResult.Failed("closed")
        val to = peer ?: return WriteResult.Failed("no peer yet")
        return withContext(Dispatchers.IO) {
            try {
                s.send(DatagramPacket(bytes, bytes.size, to))
                WriteResult.Sent
            } catch (e: Exception) {
                WriteResult.Failed(e.message ?: "UDP send failed")
            }
        }
    }

    override fun release() {
        socket?.close()
        socket = null
    }

    private companion object {
        const val MAX_DATAGRAM = 65_535
    }
}
