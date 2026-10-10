package com.altnautica.gcs.radio.rx.invite

import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.data.vault.VaultUnreadableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** An opened invite as the phone keeps it, with the ground station it came from. */
@Serializable
data class StoredInvite(
    val bundle: InviteBundle,
    @SerialName("ground_id") val groundId: String,
    @SerialName("ground_name") val groundName: String? = null,
    @SerialName("phone_fingerprint") val phoneFingerprint: String,
    @SerialName("approved_at_ms") val approvedAtMs: Long,
)

/** The phone-radio keys, sealed in the credential vault. */
@Singleton
class InviteStore @Inject constructor(private val vault: CredentialVault) {

    private val _invite = MutableStateFlow<StoredInvite?>(null)
    private var loaded = false

    /** The stored invite; call [load] once before trusting a null. */
    val invite: StateFlow<StoredInvite?> = _invite.asStateFlow()

    /**
     * The stored invite, read from the vault on the first call. Throws
     * [VaultUnreadableException] while the vault exists but cannot be read; that
     * is not cached, so a later call reads again.
     */
    suspend fun load(): StoredInvite? {
        if (!loaded) {
            // A lenient read of an unreadable vault returns null: that must not pass for "no keys".
            vault.requireReadable()
            _invite.value = vault.wfbInvite()?.let(::decode)
            loaded = true
        }
        return _invite.value
    }

    /** Reads the vault again after a passing fault, then [load]s. Throws as [load] does. */
    suspend fun reload(): StoredInvite? {
        vault.retryRead()
        return load()
    }

    /** Throws [VaultUnreadableException] when the vault refuses the write; the held invite is then unchanged. */
    suspend fun save(invite: StoredInvite) {
        vault.putWfbInvite(json.encodeToString(StoredInvite.serializer(), invite).encodeToByteArray())
        _invite.value = invite
        loaded = true
    }

    /** Forget the radio keys. Throws [VaultUnreadableException] when the vault refuses the wipe; the keys are then kept. */
    suspend fun forget() {
        vault.putWfbInvite(null)
        _invite.value = null
        loaded = true
    }

    private fun decode(blob: ByteArray): StoredInvite? = runCatching {
        json.decodeFromString(StoredInvite.serializer(), blob.decodeToString()).also { it.bundle.validate() }
    }.getOrNull()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
