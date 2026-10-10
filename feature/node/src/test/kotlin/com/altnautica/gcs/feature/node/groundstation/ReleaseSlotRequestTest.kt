package com.altnautica.gcs.feature.node.groundstation

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.http.AgentResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class ReleaseSlotRequestTest {
    private val client = mockk<AgentClient> {
        coEvery { requestJson(any(), any(), any(), any()) } returns AgentResult.Ok(JsonObject(emptyMap()))
    }

    @Test
    fun firstAttemptSendsNoOverride() = runTest {
        releaseSlotRequest(client, "drone 1", force = false)
        coVerify { client.requestJson("DELETE", "api/v1/ground-station/wfb/pair/drone%201", null, emptyMap()) }
    }

    @Test
    fun armedRetryCarriesForceInTheQuery() = runTest {
        releaseSlotRequest(client, "drone-1", force = true)
        coVerify { client.requestJson("DELETE", "api/v1/ground-station/wfb/pair/drone-1", null, mapOf("force" to "1")) }
    }
}
