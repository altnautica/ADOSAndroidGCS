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
}
