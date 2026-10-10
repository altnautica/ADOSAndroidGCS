package com.altnautica.gcs.core.agent.node

import android.os.SystemClock
import com.altnautica.gcs.core.agent.discovery.LanDiscovery
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.data.vault.NodeCredential
import com.altnautica.gcs.core.model.DeviceId
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LanNodeSourceTest {

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 100_000L
    }

    @After
    fun tearDown() = unmockkStatic(SystemClock::class)

    @Test
    fun `a paired node is listed before discovery reports anything`() = runTest {
        val cred = NodeCredential(deviceId = "d1", apiKey = "k-d1", name = "Drone", lastIp = "192.168.1.50", lastPort = 8080, profile = "drone")
        val vault = mockk<CredentialVault> { every { nodes } returns MutableStateFlow(listOf(cred)) }
        val discovery = mockk<LanDiscovery> { every { browse() } returns flow { awaitCancellation() } }
        val status = mockk<AgentStatusRepository> {
            every { observe(DeviceId("d1")) } returns flowOf(NodePoll(status = null, lastOkMs = null, carriedDroneId = null, agentVersion = null))
        }

        val nodes = LanNodeSource(vault, discovery, status).nodes.first()

        assertEquals(listOf("d1"), nodes.map { it.id.value })
    }
}
