package com.altnautica.gcs.core.agent

import com.altnautica.gcs.core.agent.http.AgentEndpoint
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentHttp
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The `/api/video/profile` calls on the wire. */
class VideoProfileClientTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun client() = AgentClient(AgentEndpoint(DeviceId("drone-1"), server.url("/"), "node-key"), AgentHttp(okhttp3.OkHttpClient()), VersionCache())

    private fun respond(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `a lease posts the profile, holder and lease and reads the applied state`() = runBlocking {
        respond(200, """{"profile":"hero","base_profile":"thumbnail","width":1280,"height":720,"fps":30,"leases":[{"holder":"android:0123456789abcdef","expires_in_s":29.9}]}""")
        val applied = client().setVideoProfile("hero", "android:0123456789abcdef", 30).getOrNull()!!
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/video/profile", request.url.encodedPath)
        assertEquals("""{"profile":"hero","holder":"android:0123456789abcdef","lease_s":30}""", request.body?.utf8())
        assertEquals("hero", applied.profile)
        assertEquals(30, applied.fps)
        assertEquals("android:0123456789abcdef", applied.leases!!.single().holder)
    }

    @Test
    fun `a plain set sends only the profile, and a pre-lease agent reads with no lease list`() = runBlocking {
        respond(200, """{"profile":"thumbnail","width":320,"height":180,"fps":1}""")
        val applied = client().setVideoProfile("thumbnail").getOrNull()!!
        assertEquals("""{"profile":"thumbnail"}""", server.takeRequest().body?.utf8())
        assertNull(applied.leases)
    }

    @Test
    fun `release deletes with the holder in the query`() = runBlocking {
        respond(200, """{"profile":"thumbnail","leases":[]}""")
        client().releaseVideoProfile("android:0123456789abcdef")
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/video/profile", request.url.encodedPath)
        assertEquals("android:0123456789abcdef", request.url.queryParameter("holder"))
    }

    @Test
    fun `a rate-limited answer surfaces as a 429`() = runBlocking {
        respond(429, """{"detail":"too many requests"}""")
        val err = (client().setVideoProfile("hero", "android:0123456789abcdef", 30) as AgentResult.Err).error
        assertEquals(429, (err as AgentError.Server).code)
    }

    @Test
    fun `status full reads the drone's live video profile`() = runBlocking {
        respond(200, """{"profile":"drone","telemetry":{"armed":false,"video_profile":"thumbnail"}}""")
        assertEquals("thumbnail", client().statusFull().getOrNull()!!.telemetry!!.videoProfile)
    }
}
