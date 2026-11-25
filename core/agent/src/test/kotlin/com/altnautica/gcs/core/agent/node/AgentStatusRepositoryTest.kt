package com.altnautica.gcs.core.agent.node

import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.model.DeviceId
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.http.AgentEndpoint
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.model.StatusFull
import io.mockk.every
import kotlinx.coroutines.flow.first
import com.altnautica.gcs.core.data.vault.NodeCredential
import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.Reach
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentStatusRepositoryTest {
    @Test
    fun `a poller leaves the map once its last observer is gone past the grace period`() = runTest {
        val clients = mockk<AgentClientFactory> { coEvery { forNode(any()) } returns null }
        val repo = AgentStatusRepository(clients)
        repo.scope = backgroundScope
        repo.clock = { 0L }

        val watcher = launch { repo.observe(DeviceId("drone-1")).collect {} }
        runCurrent()
        assertEquals(1, repo.pollerCount)

        watcher.cancel()
        advanceTimeBy(AgentStatusRepository.STOP_GRACE_MS + 1)
        runCurrent()
        assertEquals(0, repo.pollerCount)
    }

    private fun client(relayedVia: DeviceId?) = mockk<AgentClient> {
        every { endpoint } returns AgentEndpoint(DeviceId("drone-1"), "http://agent.invalid/".toHttpUrl(), "k", relayedVia = relayedVia)
        coEvery { statusFull() } returns AgentResult.Ok(StatusFull(profile = "drone"))
        coEvery { version() } returns AgentResult.Err(AgentError.Server(404, null))
    }

    private suspend fun kotlinx.coroutines.test.TestScope.firstSuccess(client: AgentClient): NodePoll {
        val clients = mockk<AgentClientFactory> { coEvery { forNode(any()) } returns client }
        val repo = AgentStatusRepository(clients)
        repo.scope = backgroundScope
        repo.clock = { 1_000L }
        return repo.observe(DeviceId("drone-1")).first { it.lastOkMs != null }
    }

    @Test
    fun `a relayed poll may be as old as two relay intervals plus a relay timeout`() = runTest {
        assertEquals(AgentStatusRepository.RELAY_MAX_POLL_AGE_MS, firstSuccess(client(relayedVia = DeviceId("gs-1"))).maxAgeMs)
        assertEquals(35_000L, AgentStatusRepository.RELAY_MAX_POLL_AGE_MS)
    }

    @Test
    fun `a direct poll keeps the lan age limit`() = runTest {
        assertEquals(AgentStatusRepository.LAN_MAX_POLL_AGE_MS, firstSuccess(client(relayedVia = null)).maxAgeMs)
        assertEquals(12_000L, AgentStatusRepository.LAN_MAX_POLL_AGE_MS)
    }

    @Test
    fun `a refused key is reported on the poll and leaves the node blocked`() = runTest {
        val refusing = mockk<AgentClient> {
            every { endpoint } returns AgentEndpoint(DeviceId("drone-1"), "http://agent.invalid/".toHttpUrl(), "old-key")
            coEvery { statusFull() } returns AgentResult.Err(AgentError.Unauthorized)
        }
        val clients = mockk<AgentClientFactory> { coEvery { forNode(any()) } returns refusing }
        val repo = AgentStatusRepository(clients)
        repo.scope = backgroundScope
        repo.clock = { 1_000L }

        val poll = repo.observe(DeviceId("drone-1")).first { it.keyRefused }
        assertTrue(poll.keyRefused)

        val cred = NodeCredential(deviceId = "drone-1", apiKey = "old-key", name = "Drone", lastIp = "192.168.1.50", lastPort = 8080, profile = "drone")
        val node = LanNodeAssembler.assemble(listOf(cred), emptyList(), mapOf("drone-1" to poll), nowMs = 1_000L).single()
        assertEquals(listOf<Reach>(Reach.Blocked(BlockReason.KeyRefused)), node.reaches)
    }
}
