package com.altnautica.gcs.core.video.whep

import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.VideoClock
import com.altnautica.gcs.core.video.VideoOutput
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.TimeUnit

/**
 * The wire format runs against a real socket server. The session's re-dial
 * policy runs against [FakeWhepEndpoint] on the test scheduler: every
 * exchange completes in virtual time, so delays and timeouts are exact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WhepSessionTest {

    private lateinit var server: MockWebServer
    private val wire = WhepSignaling(OkHttpClient())
    private val http = FakeWhepEndpoint()
    private val node = DeviceId("drone-1")

    private inner class FakePeer(val sink: VideoSink, val createdAtMs: Long) : WhepPeer {
        override val connection = MutableStateFlow(PeerLinkState.New)
        var answer: String? = null
        var closed = false

        /** Requests the endpoint had received when the peer closed. */
        var requestsAtClose = -1
        override suspend fun createOffer() = "v=0 offer"
        override suspend fun applyAnswer(sdp: String) {
            answer = sdp
            connection.value = PeerLinkState.Connected
        }
        override suspend fun latencyMs(): Int? = 42
        override fun close() {
            closed = true
            requestsAtClose = http.requests.size
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

    private fun TestScope.session(peers: MutableList<FakePeer>) =
        WhepSession(
            source = VideoSource.AgentWhep(node, BASE_URL, "main"),
            scope = backgroundScope,
            signaling = WhepSignaling(http.client, StandardTestDispatcher(testScheduler)),
            peerFactory = { sink -> FakePeer(sink, testScheduler.currentTime).also { peers += it } },
            apiKey = { "secret-key" },
            eglContext = { mockk() },
            clock = VideoClock { testScheduler.currentTime },
            recording = null,
        )

    /** Every state the session passes through, stamped with virtual time. */
    private fun TestScope.record(s: WhepSession): List<Pair<Long, VideoState>> {
        val seen = mutableListOf<Pair<Long, VideoState>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            s.state.collect { seen += testScheduler.currentTime to it }
        }
        return seen
    }

    @Test
    fun `offer posts sdp with the key and camera query`() = runTest {
        server.enqueue(answer())
        val result = wire.offer(server.url("/").toString(), "cam2", "secret-key", "v=0 offer")

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
            wire.offer(server.url("/").toString(), "main", "secret-key", "v=0 offer")
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
        repeat(3) { http.enqueue(503) }
        http.answer()
        http.enqueue(200)
        val peers = mutableListOf<FakePeer>()
        val s = session(peers)
        s.start()
        runCurrent()
        assertEquals(1, peers.size)
        assertTrue(s.state.value is VideoState.Failed)

        advanceTimeBy(7_000)
        runCurrent()

        // Backoff doubles from 1 s, and a refused POST costs no time of its own.
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), peers.map { it.createdAtMs })
        assertTrue(peers.take(3).all { it.closed })
        assertEquals("v=0 answer", peers[3].answer)
        assertEquals(List(4) { "POST" }, http.requests.map { it.method })
        s.stop()
    }

    @Test
    fun `live session goes frozen after 2_5 s without frames then redials and deletes`() = runTest {
        http.answer("/whep/first?camera=main")
        http.enqueue(200)
        http.answer("/whep/second?camera=main")
        val peers = mutableListOf<FakePeer>()
        val s = session(peers)
        val states = record(s)
        s.start()
        (s.output as VideoOutput.Sinks).addSink(mockk(relaxed = true))
        runCurrent()
        assertEquals("v=0 answer", peers.single().answer)

        peers[0].sink.onFrame(mockk<VideoFrame>(relaxed = true))
        advanceTimeBy(250)
        runCurrent()
        assertEquals(VideoState.Live(42), s.state.value)

        advanceTimeBy(2_249)
        runCurrent()
        assertTrue(states.none { it.second is VideoState.Frozen })
        assertEquals(1, peers.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2_500L to VideoState.Frozen(0), states.first { it.second is VideoState.Frozen })
        assertEquals(2, peers.size)
        assertEquals(2_500L, peers[1].createdAtMs) // the one immediate re-dial, no backoff
        assertEquals("v=0 answer", peers[1].answer)
        assertTrue(peers[0].closed)

        val (post, delete, redial) = http.requests
        assertEquals("POST", post.method)
        assertEquals("DELETE", delete.method)
        assertEquals("/whep/first?camera=main", delete.target)
        assertEquals("secret-key", delete.headers["X-ADOS-Key"])
        assertEquals("POST", redial.method)
        assertEquals(3, http.requests.size)
        s.stop()
    }

    @Test
    fun `decoded frames with no renderer on screen are not live`() = runTest {
        http.answer("/whep/only?camera=main")
        http.enqueue(200)
        val peers = mutableListOf<FakePeer>()
        val s = session(peers)
        val shown = mutableListOf<VideoFrame>()
        val renderer = VideoSink { shown += it }
        s.start()
        runCurrent()
        assertNotNull(peers.single().answer)
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
        http.answer("/whep/only?camera=main")
        http.enqueue(200)
        val peers = mutableListOf<FakePeer>()
        val s = session(peers)
        s.start()
        runCurrent()
        assertNotNull(peers.single().answer)

        s.stop()
        // The DELETE reached the endpoint while the peer was still open.
        assertEquals(2, peers[0].requestsAtClose)

        val (post, delete) = http.requests
        assertEquals("POST", post.method)
        assertEquals("DELETE", delete.method)
        assertEquals("/whep/only?camera=main", delete.target)
        assertEquals(VideoState.Idle, s.state.value)

        // Nothing re-dials after stop, however long the session sits.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, http.requests.size)
        assertEquals(1, peers.size)
    }

    private companion object {
        const val BASE_URL = "http://agent.invalid/"
    }
}
