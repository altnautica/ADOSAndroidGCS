package com.altnautica.gcs.radio.rx.invite

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.altnautica.gcs.core.data.vault.CipherEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.ProviderException
import java.util.Base64

/** A bundle that validates. */
internal fun sampleBundle() = InviteBundle(
    v = 1,
    rxKeyB64 = Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() }),
    hopKeyB64 = Base64.getEncoder().encodeToString(ByteArray(32) { 9 }),
    fleetId = 3,
    slots = listOf(InviteSlot(1, "drone-1")),
    channel = 157,
    rendezvousChannel = 149,
    ports = InvitePorts(0, 1, 2),
)

internal fun sampleInvite() = StoredInvite(sampleBundle(), "gs-1", "Field GS", "0000-0000-0000-0000", 42L)

internal class MemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { state.value = it } }
}

/** A pass-through cipher whose [open] fails with a passing provider fault while [failing] is set. */
internal class FlakyCipher : CipherEngine {
    @Volatile var failing = false
    override fun seal(plaintext: ByteArray): ByteArray = plaintext
    override fun open(sealed: ByteArray): ByteArray {
        if (failing) throw ProviderException("keystore busy")
        return sealed
    }
}
