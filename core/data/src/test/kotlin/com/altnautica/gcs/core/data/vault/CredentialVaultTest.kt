package com.altnautica.gcs.core.data.vault

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * In-memory preferences store. The file-backed store cannot be used here: on
 * Windows its atomic rename fails while the vault's collector holds the file.
 */
private class MemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { state.value = it } }
}

/** JVM AES-GCM engine standing in for the Keystore-backed one. */
private class SoftwareCipherEngine(private val key: SecretKey) : AesGcmCipherEngine() {
    override fun key(): SecretKey = key
}

private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

class CredentialVaultTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val stores = HashMap<String, MemoryPreferences>()

    private fun vault(name: String, key: SecretKey): Pair<CredentialVault, MemoryPreferences> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val store = stores.getOrPut(name) { MemoryPreferences() }
        return CredentialVault(store, SoftwareCipherEngine(key), scope) to store
    }

    private val drone = NodeCredential(
        deviceId = "abcdef0123456789",
        apiKey = "secret-key-1",
        name = "Bench drone",
        lastIp = "192.168.1.50",
        lastPort = 8080,
        profile = "drone",
    )

    @Test
    fun `node credentials round trip and are stored only as ciphertext`() = runBlocking {
        val (v, store) = vault("a", newKey())
        v.putNode(drone)
        v.putNode(drone.copy(deviceId = "gs01", apiKey = "secret-key-2", name = "Ground"))

        assertEquals("secret-key-1", v.apiKey(DeviceId("abcdef0123456789")))
        assertEquals("secret-key-2", v.apiKey(DeviceId("gs01")))
        assertNull(v.apiKey(DeviceId("unknown")))
        assertEquals(listOf("Bench drone", "Ground"), v.nodes.first { it.size == 2 }.map { it.name })

        val raw = store.data.first().asMap().values.joinToString()
        assertFalse(raw.contains("secret-key-1"))
        assertFalse(raw.contains("192.168.1.50"))
    }

    @Test
    fun `secrets survive a reopen with the same key`() = runBlocking {
        val key = newKey()
        val (first, _) = vault("b", key)
        first.putNode(drone)
        first.putWfbInvite(byteArrayOf(1, 2, 3, 4))
        first.putCloudRefreshToken("refresh-1")
        first.putMavlinkSigningKey(drone.id, ByteArray(32) { it.toByte() })
        val id = first.installId()
        scopes.forEach { it.cancel() }
        scopes.clear()

        val (second, _) = vault("b", key)
        assertEquals(drone, second.node(drone.id))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), second.wfbInvite())
        assertEquals("refresh-1", second.cloudRefreshToken())
        assertArrayEquals(ByteArray(32) { it.toByte() }, second.mavlinkSigningKey(drone.id))
        second.putMavlinkSigningKey(drone.id, null)
        assertNull(second.mavlinkSigningKey(drone.id))
        assertEquals(id, second.installId())
        assertTrue(id.startsWith("android-"))
    }

    @Test
    fun `a vault sealed under another key reads as empty`() = runBlocking {
        val (first, _) = vault("c", newKey())
        first.putNode(drone)
        scopes.forEach { it.cancel() }
        scopes.clear()

        val (second, _) = vault("c", newKey())
        assertNull(second.apiKey(drone.id))
    }

    @Test
    fun `remove, address update and wipe`() = runBlocking {
        val (v, _) = vault("d", newKey())
        v.putNode(drone)
        v.updateAddress(drone.id, lastIp = "192.168.1.51", lastPort = null)
        assertEquals("192.168.1.51", v.node(drone.id)?.lastIp)
        assertEquals(8080, v.node(drone.id)?.lastPort)

        val id = v.installId()
        v.putCloudRefreshToken("r")
        v.wipe()
        assertNull(v.node(drone.id))
        assertNull(v.cloudRefreshToken())
        assertEquals(id, v.installId())

        v.putNode(drone)
        v.removeNode(drone.id)
        assertNull(v.apiKey(drone.id))
    }
}
