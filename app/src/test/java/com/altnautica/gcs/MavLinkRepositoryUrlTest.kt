package com.altnautica.gcs

import com.altnautica.gcs.data.flightlog.TlogRecorder
import com.altnautica.gcs.data.mavlink.MavLinkParser
import com.altnautica.gcs.data.mavlink.MavLinkRepository
import com.altnautica.gcs.data.pairing.AgentCredentialStore
import com.altnautica.gcs.data.telemetry.TelemetryStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class MavLinkRepositoryUrlTest {

    @Test
    fun `default ws url targets the agent mavlink listener at the host root`() {
        val repo = newRepository()

        // The control-surface path this used to dial is served by nothing; the
        // MAVLink proxy is its own listener at the host root.
        assertEquals("ws://192.168.4.1:8765/", repo.wsUrl.value)
    }

    @Test
    fun `setUrl updates wsUrl flow`() {
        val repo = newRepository()

        val updated = "ws://10.0.0.5:8765/"
        repo.setUrl(updated)

        assertEquals(updated, repo.wsUrl.value)
    }

    private fun newRepository(): MavLinkRepository {
        // The HttpClient is only constructed; no calls run during these
        // URL-only assertions.
        val client = HttpClient(OkHttp)
        val parser: MavLinkParser = mockk(relaxed = true)
        val store = TelemetryStore()
        val tlog: TlogRecorder = mockk(relaxed = true)
        val credentials: AgentCredentialStore = mockk(relaxed = true)
        return MavLinkRepository(client, parser, store, tlog, credentials)
    }
}
