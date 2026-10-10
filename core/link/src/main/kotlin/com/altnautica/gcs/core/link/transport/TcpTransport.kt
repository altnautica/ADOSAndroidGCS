package com.altnautica.gcs.core.link.transport

import com.altnautica.gcs.core.model.TransportState
import com.altnautica.gcs.core.model.WriteResult
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** TCP client to `host:port` (SITL and many telemetry bridges listen on 5760). */
class TcpTransport(
    context: CoroutineContext,
    private val host: String,
    private val port: Int,
) : ChannelTransport(context) {

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private val writeLock = Mutex()

    suspend fun open() {
        mutableState.value = TransportState.Connecting
        val s = withContext(Dispatchers.IO) {
            try {
                Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                }
            } catch (e: Exception) {
                null.also { finish(TransportState.Failed(e.message ?: "TCP connect failed")) }
            }
        } ?: return
        socket = s
        output = s.getOutputStream()
        mutableState.value = TransportState.Open
        scope.launch(Dispatchers.IO) {
            val input = s.getInputStream()
            val buf = ByteArray(4096)
            while (isActive) {
                val n = try {
                    input.read(buf)
                } catch (e: Exception) {
                    -2
                }
                if (n < 0) {
                    finish(TransportState.Closed(if (n == -1) "peer closed" else "read failed"))
                    break
                }
                if (n > 0) deliver(buf.copyOf(n))
            }
        }
    }

    override suspend fun write(bytes: ByteArray): WriteResult {
        val out = output ?: return WriteResult.Failed("closed")
        return withContext(Dispatchers.IO) {
            writeLock.withLock {
                try {
                    out.write(bytes)
                    out.flush()
                    WriteResult.Sent
                } catch (e: Exception) {
                    finish(TransportState.Closed(e.message))
                    WriteResult.Failed(e.message ?: "TCP write failed")
                }
            }
        }
    }

    override fun release() {
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}
