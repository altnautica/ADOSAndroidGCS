package com.altnautica.gcs.core.agent

import com.altnautica.gcs.core.agent.http.AgentEndpoint
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentHttp
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentClientTest {

    private val server = MockWebServer()
    private val http = AgentHttp(OkHttpClient())
    private val cache = VersionCache()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun client(key: String? = "node-key") =
        AgentClient(AgentEndpoint(DeviceId("gs01"), server.url("/"), key), http, cache)

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun RecordedRequest.text(): String = body?.utf8() ?: ""

    @Test
    fun `requests carry the node key header`() = runBlocking {
        respond(200, """{"api_version":"1","agent_version":"1.2.3","capabilities":["status.full"]}""")
        val result = client().version()
        assertEquals("1.2.3", result.getOrNull()?.agentVersion)
        val request = server.takeRequest()
        assertEquals("node-key", request.headers["X-ADOS-Key"])
        assertEquals("/api/version", request.url.encodedPath)
    }

    @Test
    fun `no header while unpaired`() = runBlocking {
        respond(200, """{"paired":false,"pairing_code":"K7M2QX"}""")
        client(key = null).pairingInfo()
        assertNull(server.takeRequest().headers["X-ADOS-Key"])
    }

    @Test
    fun `version is cached per endpoint`() = runBlocking {
        respond(200, """{"agent_version":"1.2.3"}""")
        client().version()
        client().version()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `carried drone calls go through the relay prefix with the ground key`() = runBlocking {
        respond(200, """{"status":"ok","cmd":"land"}""")
        val factory = AgentClientFactory(http, endpointsUnused(), cache)
        val relayed = factory.forCarried(client(), DeviceId("drone 7"))
        val result = relayed.command("land")
        assertTrue(result.getOrNull()!!.accepted)
        val request = server.takeRequest()
        assertEquals("/api/v1/ground-station/relay-proxy/drone%207/api/command", request.url.encodedPath)
        assertEquals("node-key", request.headers["X-ADOS-Key"])
        assertTrue(relayed.endpoint.isRelayed)
    }

    @Test
    fun `armed refusal and forced retry`() = runBlocking {
        respond(409, """{"error":"E_ARMED","message":"Vehicle is armed","override":"force"}""")
        respond(200, """{"ok":true}""")
        val refused = client().putConfig("video.bitrate", JsonPrimitive(4000))
        assertEquals(AgentResult.Err(AgentError.Armed(canForce = true)), refused)
        assertEquals("""{"key":"video.bitrate","value":4000}""", server.takeRequest().text())

        client().putConfig("video.bitrate", JsonPrimitive(4000), force = true)
        assertEquals("""{"key":"video.bitrate","value":4000,"force":true}""", server.takeRequest().text())
    }

    @Test
    fun `bodiless forced write sends force in the body`() = runBlocking {
        respond(200, "{}")
        client().restartSupervisor(force = true)
        assertEquals("""{"force":true}""", server.takeRequest().text())
    }

    @Test
    fun `wfb put sends only channel and optional force`() = runBlocking {
        respond(200, """{"channel":149,"persisted":true}""")
        respond(200, """{"channel":161,"persisted":true}""")
        assertEquals(149, client().groundStation.putWfb(149).getOrNull()?.channel)
        val first = server.takeRequest()
        assertEquals("PUT", first.method)
        assertEquals("/api/v1/ground-station/wfb", first.url.encodedPath)
        assertEquals("""{"channel":149}""", first.text())

        client().groundStation.putWfb(161, force = true)
        assertEquals("""{"channel":161,"force":true}""", server.takeRequest().text())
    }

    @Test
    fun `camera switch keeps an unobserved acknowledgement null`() = runBlocking {
        respond(200, """{"camera_id":"2","sent":true,"accepted":null,"reason":"not observed"}""")
        val response = client().groundStation.switchCamera("2").getOrNull()!!
        assertEquals(true, response.sent)
        assertNull(response.accepted)
        assertEquals("""{"camera_id":"2"}""", server.takeRequest().text())
    }

    @Test
    fun `camera switch without multi camera support is feature absent`() = runBlocking {
        respond(501, """{"detail":"drone does not advertise multi-camera support"}""")
        // A 501 with a detail string and no code is still an absent feature.
        assertEquals(AgentResult.Err(AgentError.FeatureAbsent), client().groundStation.switchCamera("2"))
    }

    @Test
    fun `profile mismatch on a drone`() = runBlocking {
        respond(404, """{"detail":{"error":{"code":"E_PROFILE_MISMATCH"}}}""")
        assertEquals(AgentResult.Err(AgentError.ProfileMismatch), client().groundStation.status())
    }

    @Test
    fun `status full with nulls and unknown fields decodes`() = runBlocking {
        respond(
            200,
            """{"version":"1.2.3","fcVariant":null,"fcFirmware":"ardupilot","profile":"drone","newField":{"x":1},
               "video":{"state":"running","whep_url":"/whep","streams":[{"id":"main","role":"eo","codec":"h265","live":null}]},
               "telemetry":{"armed":false,"battery":{"voltage":null,"remaining":-1}}}""",
        )
        val status = client().statusFull().getOrNull()!!
        assertEquals("ardupilot", status.fcFirmware)
        assertNull(status.video?.streams?.first()?.live)
        assertEquals(-1, status.telemetry?.battery?.remaining)
    }

    @Test
    fun `unreachable node is a network error`() = runBlocking {
        server.close()
        val result = client().status()
        assertTrue((result as AgentResult.Err).error is AgentError.Network)
    }

    @Test
    fun `plugin config write body and encoded asset path`() = runBlocking {
        respond(200, "{}")
        respond(200, "export default 1")
        client().plugins.putConfig("follow-me", "active", JsonPrimitive(true))
        val write = server.takeRequest()
        assertEquals("/api/plugins/follow-me/config", write.url.encodedPath)
        assertEquals("""{"key":"active","value":true}""", write.text())

        val asset = client().plugins.gcsAsset("follow-me", "dist/panel main.js").getOrNull()!!
        assertEquals("export default 1", asset.text)
        assertEquals("/api/plugins/follow-me/gcs/dist/panel%20main.js", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `catalog install outlasts the LAN budget and pins the entry`() = runBlocking {
        // The agent downloads and installs inside the request and aborts when the caller hangs up.
        server.enqueue(
            MockResponse.Builder().code(200).body("""{"plugin_id":"follow-me"}""")
                .headersDelay(AgentHttp.LAN_TIMEOUT_S + 1, java.util.concurrent.TimeUnit.SECONDS).build(),
        )
        val result = client().plugins.installFromCatalog("https://example.com/f.adosplug", "ab", "job-1")
        assertTrue(result is AgentResult.Ok)
        val request = server.takeRequest()
        assertEquals("/api/plugins/install_from_url", request.url.encodedPath)
        assertEquals(
            """{"url":"https://example.com/f.adosplug","expected_sha256":"ab","from_catalog":true,"job_id":"job-1"}""",
            request.text(),
        )
    }

    private fun endpointsUnused(): AgentEndpoints = io.mockk.mockk(relaxed = true)
}
