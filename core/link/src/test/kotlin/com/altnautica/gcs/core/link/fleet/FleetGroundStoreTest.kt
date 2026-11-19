package com.altnautica.gcs.core.link.fleet

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.altnautica.gcs.core.data.vault.CipherEngine
import com.altnautica.gcs.core.data.vault.CredentialVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetGroundStoreTest {

    @Test
    fun `a bind's keys persist across a reopen`() = runTest {
        val prefs = MemoryPreferences()
        FleetGroundStore(vault(prefs)).adopt(keys(1))

        val fleet = FleetGroundStore(vault(prefs)).load()!!
        assertArrayEquals(ByteArray(64) { 1 }, fleet.gsKey)
        assertArrayEquals(ByteArray(32) { 1 }, fleet.pairKey)
        assertEquals(1, fleet.fleetId)
        assertEquals(149, fleet.channel)
        assertFalse(fleet.keyProven)
        assertEquals("fp1", fleet.keyFingerprint)
    }

    @Test
    fun `a drone heard on the air is enrolled once and keeps its slot`() = runTest {
        val store = FleetGroundStore(vault(MemoryPreferences()))
        store.adopt(keys(1))
        assertEquals(1, store.enrol("drone-aa01"))
        assertEquals(2, store.enrol("drone-bb02"))
        val secret = store.fleet.value!!.slots.first().relaySecret
        assertEquals(1, store.enrol("drone-aa01"))
        assertEquals(secret, store.fleet.value!!.slots.first().relaySecret)
        assertEquals(2, store.fleet.value!!.slots.size)
    }

    @Test
    fun `an id that cannot name an aircraft is never enrolled`() = runTest {
        val store = FleetGroundStore(vault(MemoryPreferences()))
        store.adopt(keys(1))
        for (bad in listOf("", "d", "drone-1", "drone 0001", "a".repeat(33))) assertNull(bad, store.enrol(bad))
        assertTrue(store.fleet.value!!.slots.isEmpty())
    }

    @Test
    fun `nothing is enrolled without a bound fleet`() = runTest {
        val store = FleetGroundStore(vault(MemoryPreferences()))
        assertNull(store.enrol("drone-aa01"))
        assertNull(store.load())
    }

    @Test
    fun `a full fleet declines a newcomer rather than evicting`() = runTest {
        val store = FleetGroundStore(vault(MemoryPreferences()))
        store.adopt(keys(1))
        for (i in 1..FleetRegistry.MAX_SLOTS) store.enrol("drone-%02d00".format(i))
        assertNull(store.enrol("one-too-many"))
        assertEquals(1, store.enrol("drone-0100"))
    }

    @Test
    fun `the latch holds and the keys are reused for the next drone`() = runTest {
        val store = FleetGroundStore(vault(MemoryPreferences()))
        store.adopt(keys(1))
        assertNull("a never-proven, empty fleet mints fresh keys", store.reusableKeys())
        store.markKeyProven()
        assertTrue(store.load()!!.keyProven)
        assertArrayEquals(ByteArray(64) { 1 }, store.reusableKeys()!!.droneKey)

        // Re-adopting the same keys (a second drone) keeps the latch and the registry.
        store.enrol("drone-aa01")
        store.adopt(keys(1))
        assertTrue(store.load()!!.keyProven)
        assertEquals(1, store.load()!!.slots.size)
    }

    @Test
    fun `an enrolled fleet reuses its keys even before the latch`() = runTest {
        val store = FleetGroundStore(vault(MemoryPreferences()))
        store.adopt(keys(1))
        store.enrol("drone-aa01")
        assertArrayEquals(ByteArray(64) { 1 }, store.reusableKeys()!!.droneKey)
    }

    @Test
    fun `new keys start a new fleet`() = runTest {
        val store = FleetGroundStore(vault(MemoryPreferences()))
        store.adopt(keys(1))
        store.enrol("drone-aa01")
        store.markKeyProven()
        store.adopt(keys(2))
        val fleet = store.load()!!
        assertFalse(fleet.keyProven)
        assertTrue(fleet.slots.isEmpty())
    }

    @Test
    fun `forget wipes the fleet`() = runTest {
        val prefs = MemoryPreferences()
        val store = FleetGroundStore(vault(prefs))
        store.adopt(keys(1))
        store.forget()
        assertNull(store.fleet.value)
        assertNull(FleetGroundStore(vault(prefs)).load())
    }

    private fun keys(n: Byte) = FleetKeys(ByteArray(64) { n }, ByteArray(64) { n }, ByteArray(32) { n }, "fp$n")

    private fun kotlinx.coroutines.test.TestScope.vault(prefs: MemoryPreferences) =
        CredentialVault(prefs, PlainCipher, CoroutineScope(backgroundScope.coroutineContext))
}

internal object PlainCipher : CipherEngine {
    override fun seal(plaintext: ByteArray): ByteArray = plaintext
    override fun open(sealed: ByteArray): ByteArray = sealed
}

internal class MemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { state.value = it } }
}
