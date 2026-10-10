package com.altnautica.gcs.core.video.whep

import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.VideoClock
import com.altnautica.gcs.core.video.VideoOutput
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class WhepSessionTest {

    private lateinit var server: MockWebServer
    private val signaling = WhepSignaling(OkHttpClient())
    private val node = DeviceId("drone-1")

    private inner class FakePeer(val sink: VideoSink, val createdAtMs: Long) : WhepPeer {
        override val connection = MutableStateFlow(PeerLinkState.New)
        var answer: String? = null
        var closed = false

        /** Requests the server had received when the peer closed. */
        var requestsAtClose = -1
        override suspend fun createOffer() = "v=0 offer"
        override suspend fun applyAnswer(sdp: String) {
            answer = sdp
            connection.value = PeerLinkState.Connected
        }
        override suspend fun latencyMs(): Int? = 42
        override fun close() {
            closed = true
            requestsAtClose = server.requestCount
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.close()

    private fun answer(location: String = "/whep/abc?camera=main") =
        MockResponse.Builder().code(201).addHeader("Location", location).body("v=0 answer").build()

    private fun TestScope.session(peers: MutableList<FakePeer>, scope: CoroutineScope = backgroundScope) =
        WhepSession(
            source = VideoSource.AgentWhep(node, server.url("/").toString(), "main"),
            scope = scope,
            signaling = signaling,
            peerFactory = { sink -> FakePeer(sink, testScheduler.currentTime).also { peers += it } },
            apiKey = { "secret-key" },
            eglContext = { mockk() },
            clock = VideoClock { testScheduler.currentTime },
            recording = null,
        )

    private suspend fun eventually(cond: () -> Boolean) {
        withTimeout(120_000) { while (!cond()) { Thread.sleep(1); delay(10) } }
    }

    @Test
    fun `offer posts sdp with the key and camera query`() = runTest {
        server.enqueue(answer())
        val result = signaling.offer(server.url("/").toString(), "cam2", "secret-key", "v=0 offer")

        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/whep?camera=cam2", req.target)
        assertEquals("secret-key", req.headers["X-ADOS-Key"])
        assertTrue(req.headers["Content-Type"]!!.startsWith("application/sdp"))
        assertEquals("v=0 offer", req.body!!.utf8())
        assertEquals("v=0 answer", result.sdp)
        assertEquals(server.url("/whep/abc?camera=main"), result.resourceUrl)
    }

    @Test
    fun `location resolves relative and absolute`() {
        val req = "http://192.168.1.50:8080/whep?camera=main".toHttpUrl()
        assertEquals(
            "http://192.168.1.50:8080/whep/s1?camera=main".toHttpUrl(),
            WhepSignaling.resolveResource(req, "/whep/s1?camera=main"),
        )
        assertEquals(
            "http://192.168.1.60:8889/main/whep/s2".toHttpUrl(),
            WhepSignaling.resolveResource(req, "http://192.168.1.60:8889/main/whep/s2"),
        )
        assertEquals("http://192.168.1.50:8080/whep".toHttpUrl(), WhepSignaling.whepUrl("http://192.168.1.50:8080", null))
    }

    @Test
    fun `an offer cancelled mid-POST deletes the session the agent created`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(201).addHeader("Location", "/whep/late?camera=main").body("v=0 answer")
                .headersDelay(800, TimeUnit.MILLISECONDS).build(),
        )
        server.enqueue(MockResponse.Builder().code(200).build())
        val dial = launch(Dispatchers.IO) {
            signaling.offer(server.url("/").toString(), "main", "secret-key", "v=0 offer")
        }
        delay(200)
        dial.cancelAndJoin()

        assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
        val delete = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("DELETE", delete.method)
        assertEquals("/whep/late?camera=main", delete.target)
        assertEquals("secret-key", delete.headers["X-ADOS-Key"])
        assertTrue(dial.isCancelled)
    }

    @Test
    fun `refused offer fails then redials with backoff`() = runTest {
        repeat(3) { server.enqueue(MockResponse.Builder().code(503).build()) }
        server.enqueue(answer())
        val peers = mutableListOf<FakePeer>()
        val s = session(peers)
        s.start()

        eventually { peers.size >= 4 }

        // Each gap is the backoff delay plus the (virtual) time spent waiting on the refused POST.
        val gaps = peers.map { it.createdAtMs }.zipWithNext { a, b -> b - a }
        listOf(1_000L, 2_000L, 4_000L).zip(gaps).forEach { (want, got) ->
            assertTrue("gap $got for $want", got in want until want + 500)
        }
        assertTrue(peers.take(3).all { it.closed })
        s.stop()
    }

    @Test
    fun `live session goes frozen after 2_5 s without frames then redials and deletes`() = runTest {
        server.enqueue(answer("/whep/first?camera=main"))
        server.enqueue(MockResponse.Builder().code(200).build())
        server.enqueue(answer("/whep/second?camera=main"))
        val peers = mutableListOf<FakePeer>()
        val s = session(peers)
        s.start()
        (s.output as VideoOutput.Sinks).addSink(mockk(relaxed = true))

        eventually { peers.isNotEmpty() && peers[0].answer != null }
        peers[0].sink.onFrame(mockk<VideoFrame>(relaxed = true))
        assertEquals(VideoState.Live(42), withTimeout(60_000) { s.state.first { it is VideoState.Live } })

        val frozen = withTimeout(60_000) { s.state.first { it is VideoState.Frozen } } as VideoState.Frozen
        assertTrue(testScheduler.currentTime - frozen.sinceMs >= 2_500)
        eventually { peers.size >= 2 && peers[1].answer != null }

        assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
        val delete = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("DELETE", delete.method)
        assertEquals("/whep/first?camera=main", delete.target)
        assertEquals("secret-key", delete.headers["X-ADOS-Key"])
        assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)!!.method) // the one immediate re-dial
        assertTrue(peers[0].closed)
        s.stop()
    }

    @Test
    fun `decoded frames with no renderer on screen are not live`() = runTest {
        server.enqueue(answer("/whep/only?camera=main"))
        server.enqueue(MockResponse.Builder().code(200).build())
        val peers = mutableListOf<FakePeer>()
        val s = session(peers)
        val shown = mutableListOf<VideoFrame>()
        val renderer = VideoSink { shown += it }
        s.start()
        eventually { peers.isNotEmpty() && peers[0].answer != null }
        val sink = peers[0].sink

        // Frames decode but nothing draws them: the band must not say LIVE.
        repeat(8) {
            sink.onFrame(mockk(relaxed = true))
            delay(250)
        }
        assertEquals(VideoState.Connecting, s.state.value)
        assertTrue(shown.isEmpty())

        // A renderer with a surface attaches: the next frames are shown and LIVE.
        (s.output as VideoOutput.Sinks).addSink(renderer)
        repeat(4) {
            sink.onFrame(mockk(relaxed = true))
            delay(250)
        }
        assertEquals(VideoState.Live(42), s.state.value)
        assertEquals(4, shown.size)

        // Its surface goes away while decoding continues: back to connecting, same peer.
        (s.output as VideoOutput.Sinks).removeSink(renderer)
        repeat(16) {
            sink.onFrame(mockk(relaxed = true))
            delay(250)
        }
        assertEquals(VideoState.Connecting, s.state.value)
        assertEquals(1, peers.size)
        s.stop()
    }

    @Test
    fun `stop deletes the session resource`() = runTest {
        server.enqueue(answer("/whep/only?camera=main"))
        server.enqueue(MockResponse.Builder().code(200).build())
        val peers = mutableListOf<FakePeer>()
        val s = session(peers)
        s.start()
        eventually { peers.isNotEmpty() && peers[0].answer != null }

        s.stop()
        // The DELETE reached the server while the peer was still open.
        assertEquals(2, peers[0].requestsAtClose)

        assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
        val delete = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("DELETE", delete.method)
        assertEquals("/whep/only?camera=main", delete.target)
        assertEquals(VideoState.Idle, s.state.value)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }
}
