package com.altnautica.gcs.core.agent.pairing

import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.AgentEndpoints
import com.altnautica.gcs.core.agent.VersionCache
import com.altnautica.gcs.core.agent.discovery.LanDiscovery
import com.altnautica.gcs.core.agent.http.AgentEndpoint
import com.altnautica.gcs.core.agent.http.AgentHttp
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.data.vault.NodeCredential
import com.altnautica.gcs.core.data.vault.VaultUnreadableException
import com.altnautica.gcs.core.model.DeviceId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A credential store that cannot be read never crashes pairing and never loses a key an agent handed out. */
class PairingServiceVaultTest {

    private val server = MockWebServer()
    private val vault = mockk<CredentialVault>()
    private val endpoints = mockk<AgentEndpoints>()
    private lateinit var service: PairingService
    private lateinit var host: String

    private val unreadable get() = VaultUnreadableException(java.security.ProviderException("keystore busy"))

    @Before
    fun setUp() {
        server.start()
        host = "${server.hostName}:${server.port}"
        val factory = AgentClientFactory(AgentHttp(OkHttpClient()), endpoints, VersionCache())
        service = PairingService(factory, mockk<LanDiscovery>(relaxed = true), vault)
        coEvery { vault.requireWritable() } just runs
        coEvery { vault.installId() } returns "android-install"
        coEvery { endpoints.endpointFor(DeviceId("d1")) } returns AgentEndpoint(DeviceId("d1"), server.url("/"), "key-1")
    }

    @After
    fun tearDown() = server.close()

    private fun respond(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun paths(): List<String> = List(server.requestCount) { server.takeRequest().url.encodedPath }

    @Test
    fun `claim stops before the agent when the vault would refuse the key`() = runBlocking {
        coEvery { vault.requireWritable() } throws unreadable
        respond(200, """{"device_id":"d1","paired":false}""")

        assertEquals(PairingOutcome.Failure(PairingFailure.VaultUnavailable), service.claim(host))
        assertEquals(listOf("/api/pairing/info"), paths())
    }

    @Test
    fun `a claimed key the vault refused is saved by the retry without claiming again`() = runBlocking {
        var failures = 1
        val saved = mutableListOf<NodeCredential>()
        coEvery { vault.putNode(any()) } coAnswers {
            if (failures-- > 0) throw unreadable
            saved += firstArg<NodeCredential>()
        }
        respond(200, """{"device_id":"d1","paired":false}""")
        respond(200, """{"api_key":"key-1","device_id":"d1","name":"Drone"}""")

        assertEquals(PairingOutcome.Failure(PairingFailure.VaultUnavailable), service.claim(host))
        assertTrue(service.hasUnsavedClaim(host))
        val retry = service.claim(host)
        assertFalse(service.hasUnsavedClaim(host))

        assertTrue(retry is PairingOutcome.Success)
        assertEquals("key-1", saved.single().apiKey)
        assertEquals(listOf("/api/pairing/info", "/api/pairing/claim"), paths())
    }

    @Test
    fun `adding by key reports the vault instead of throwing`() = runBlocking {
        coEvery { vault.putNode(any()) } throws unreadable
        respond(200, """{"device_id":"d1","paired":true}""")
        respond(200, """{"status":"ok"}""")

        assertEquals(PairingOutcome.Failure(PairingFailure.VaultUnavailable), service.addWithKey(host, "key-1"))
    }

    @Test
    fun `unpair released on the agent retries only the local removal`() = runBlocking {
        var failures = 1
        coEvery { vault.removeNode(DeviceId("d1")) } coAnswers { if (failures-- > 0) throw unreadable }
        respond(200, """{"status":"unpaired","new_code":"K7M2QX"}""")

        assertEquals(PairingOutcome.Failure(PairingFailure.VaultUnavailable), service.unpair(DeviceId("d1")))
        assertEquals(PairingOutcome.Success(Unit), service.unpair(DeviceId("d1")))
        assertEquals(listOf("/api/pairing/unpair"), paths())
        coVerify(exactly = 2) { vault.removeNode(DeviceId("d1")) }
    }

    @Test
    fun `unpair does not release the agent while the vault cannot drop the key`() = runBlocking {
        coEvery { vault.requireWritable() } throws unreadable

        assertEquals(PairingOutcome.Failure(PairingFailure.VaultUnavailable), service.unpair(DeviceId("d1")))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `forget reports the vault instead of throwing`() = runBlocking {
        coEvery { vault.removeNode(any()) } throws unreadable
        assertEquals(PairingOutcome.Failure(PairingFailure.VaultUnavailable), service.forget(DeviceId("d1")))
    }
}
