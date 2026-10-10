package com.altnautica.gcs.core.agent.node

import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.altnautica.gcs.core.agent.discovery.LanDiscovery
import com.altnautica.gcs.core.data.vault.CipherEngine
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.data.vault.NodeCredential
import com.altnautica.gcs.core.model.DeviceId
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val vault = mockk<CredentialVault> {
            every { nodes } returns MutableStateFlow(listOf(cred))
            every { unreadable } returns MutableStateFlow(false)
        }
        val discovery = mockk<LanDiscovery> { every { browse() } returns flow { awaitCancellation() } }
        val status = mockk<AgentStatusRepository> {
            every { observe(DeviceId("d1")) } returns flowOf(NodePoll(status = null, lastOkMs = null, carriedDroneId = null, agentVersion = null))
        }

        val nodes = LanNodeSource(vault, discovery, status).nodes.first()

        assertEquals(listOf("d1"), nodes.map { it.id.value })
    }

    @Test
    fun `paired nodes behind an unreadable vault are reported, then listed after a retry`() = runBlocking {
        val store = MemoryPreferences()
        val cipher = FlakyCipher()
        val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        CredentialVault(store, cipher, writer).putNode(NodeCredential(deviceId = "d1", apiKey = "k-d1", name = "Drone", profile = "drone"))
        writer.cancel()

        cipher.failing = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val discovery = mockk<LanDiscovery> { every { browse() } returns flow { awaitCancellation() } }
            val status = mockk<AgentStatusRepository> {
                every { observe(DeviceId("d1")) } returns flowOf(NodePoll(status = null, lastOkMs = null, carriedDroneId = null, agentVersion = null))
            }
            val source = LanNodeSource(CredentialVault(store, cipher, scope), discovery, status)

            assertTrue(source.savedNodesUnreadable.first { it })
            assertTrue(source.nodes.first().isEmpty())
            assertFalse(source.retrySavedNodes())

            cipher.failing = false
            assertTrue(source.retrySavedNodes())
            assertFalse(source.savedNodesUnreadable.value)
            assertEquals(listOf("d1"), source.nodes.first { it.isNotEmpty() }.map { it.id.value })
        } finally {
            scope.cancel()
        }
    }
}

private class MemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { state.value = it } }
}

/** Plain "cipher" whose [open] fails with a passing provider fault while [failing] is set. */
private class FlakyCipher : CipherEngine {
    @Volatile var failing = false
    override fun seal(plaintext: ByteArray): ByteArray = plaintext
    override fun open(sealed: ByteArray): ByteArray {
        if (failing) throw java.security.ProviderException("keystore temporarily unavailable")
        return sealed
    }
}
