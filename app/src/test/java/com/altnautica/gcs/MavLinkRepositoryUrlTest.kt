package com.altnautica.gcs

import com.altnautica.gcs.data.flightlog.TlogRecorder
import com.altnautica.gcs.data.mavlink.MavLinkParser
import com.altnautica.gcs.data.mavlink.MavLinkRepository
import com.altnautica.gcs.data.telemetry.TelemetryStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MavLinkRepositoryUrlTest {

    @Test
    fun `default ws url targets ground-station mavlink endpoint`() {
        val repo = newRepository()

        val expected = "ws://192.168.4.1:8080/api/v1/ground-station/ws/mavlink"
        assertEquals(expected, repo.wsUrl.value)
    }

    @Test
    fun `setUrl updates wsUrl flow`() {
        val repo = newRepository()

        val updated = "ws://10.0.0.5:8080/api/v1/ground-station/ws/mavlink"
        repo.setUrl(updated)

        assertEquals(updated, repo.wsUrl.value)
    }

    @Test
    fun `setUrl preserves the canonical path component`() {
        val repo = newRepository()
        repo.setUrl("ws://10.1.1.1:8080/api/v1/ground-station/ws/mavlink")

        assertTrue(repo.wsUrl.value.endsWith("/api/v1/ground-station/ws/mavlink"))
    }

    private fun newRepository(): MavLinkRepository {
        // The HttpClient is only constructed; no calls run during these
        // URL-only assertions.
        val client = HttpClient(OkHttp)
        val parser: MavLinkParser = mockk(relaxed = true)
        val store = TelemetryStore()
        val tlog: TlogRecorder = mockk(relaxed = true)
        return MavLinkRepository(client, parser, store, tlog)
    }
}
