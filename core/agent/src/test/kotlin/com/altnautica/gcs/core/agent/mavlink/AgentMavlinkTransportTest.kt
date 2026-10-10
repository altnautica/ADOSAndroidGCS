package com.altnautica.gcs.core.agent.mavlink

import com.altnautica.gcs.core.agent.http.AgentEndpoint
import com.altnautica.gcs.core.model.TransportState
import com.altnautica.gcs.core.model.WriteResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentMavlinkTransportTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun endpoint() = AgentEndpoint(null, server.url("/"), "node-key")

    @Test
    fun `binary frames flow both ways with the key on the handshake`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        webSocket.send(bytes)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                    }
                },
            ).build(),
        )
        // The test server listens on one port; point the transport straight at it.
        val transport = AgentMavlinkTransport(OkHttpClient(), resolve = { endpoint() }, wsUrlOverride = server.url("/").toString().replaceFirst("http", "ws"))
        withTimeout(5_000) {
            transport.state.first { it == TransportState.Open }
            val echoed = async(start = CoroutineStart.UNDISPATCHED) { transport.incoming.first() }
            assertEquals(WriteResult.Sent, transport.write(byteArrayOf(0xFD.toByte(), 9, 8)))
            assertArrayEquals(byteArrayOf(0xFD.toByte(), 9, 8), echoed.await())
        }
        assertEquals("node-key", server.takeRequest().headers["X-ADOS-Key"])
        transport.close()
        assertTrue(transport.state.value is TransportState.Closed)
    }

    @Test
    fun `refused key fails without retrying`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())
        val transport = AgentMavlinkTransport(OkHttpClient(), resolve = { endpoint() }, wsUrlOverride = server.url("/").toString().replaceFirst("http", "ws"))
        withTimeout(5_000) { transport.state.first { it is TransportState.Failed } }
        assertEquals(1, server.requestCount)
        assertTrue(transport.write(byteArrayOf(1)) is WriteResult.Failed)
        transport.close()
    }

    @Test
    fun `unpaired node fails immediately`() = runBlocking {
        val transport = AgentMavlinkTransport(OkHttpClient(), resolve = { null })
        withTimeout(5_000) { transport.state.first { it is TransportState.Failed } }
        transport.close()
    }
}
