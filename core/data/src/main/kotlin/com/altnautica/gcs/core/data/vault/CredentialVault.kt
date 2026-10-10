package com.altnautica.gcs.core.data.vault

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.UnrecoverableKeyException
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException

/**
 * One paired agent node. [apiKey] is the node's full-authority key; it never
 * leaves the vault except in the `X-ADOS-Key` header of a request to that node.
 */
@Serializable
data class NodeCredential(
    @SerialName("device_id") val deviceId: String,
    @SerialName("api_key") val apiKey: String,
    val name: String? = null,
    @SerialName("mdns_host") val mdnsHost: String? = null,
    @SerialName("last_ip") val lastIp: String? = null,
    @SerialName("last_port") val lastPort: Int? = null,
    val profile: String? = null,
) {
    val id: DeviceId get() = DeviceId(deviceId)
}

/** The whole vault, sealed as one document so every write is atomic. */
@Serializable
internal data class VaultDocument(
    val nodes: Map<String, NodeCredential> = emptyMap(),
    @SerialName("wfb_invite") val wfbInvite: String? = null,
    @SerialName("cloud_refresh_token") val cloudRefreshToken: String? = null,
    @SerialName("install_id") val installId: String? = null,
    @SerialName("mavlink_signing_keys") val mavlinkSigningKeys: Map<String, String> = emptyMap(),
)

/**
 * The stored vault exists but could not be read for a reason that may pass
 * (Keystore busy or unavailable, a provider fault, a malformed document). The
 * vault refuses to write over it, because that write would replace every
 * other secret with only the one being changed.
 */
class VaultUnreadableException(cause: Throwable) : IOException("Credential vault could not be read", cause)

/**
 * Encrypted store for every secret the app holds: per-node agent API keys,
 * the phone-radio invite material, and the cloud refresh token.
 *
 * The plaintext is one JSON document sealed with [CipherEngine] (an AES-256-GCM
 * key in the Android Keystore in production) and kept as base64 in a dedicated
 * DataStore file. A document whose key is definitively gone (a different key
 * fails authentication after a backup restore onto another device, or the
 * Keystore reports the key unrecoverable or invalidated) is treated as empty:
 * the operator re-pairs, which is the only safe recovery for a secret that
 * cannot be read; the old ciphertext is kept under [LOST_DOCUMENT_KEY]. Any
 * other read failure is not a reset: reads fall back to the last good
 * document (or empty, uncached) and writes throw [VaultUnreadableException].
 */
class CredentialVault(
    private val store: DataStore<Preferences>,
    private val cipher: CipherEngine,
    scope: CoroutineScope,
) {

    private val writeLock = Mutex()
    private val document = MutableStateFlow<VaultDocument?>(null)
    private val _nodes = MutableStateFlow<List<NodeCredential>>(emptyList())
    private val _unreadable = MutableStateFlow(false)

    /** Every paired node, ordered by name then id. */
    val nodes: StateFlow<List<NodeCredential>> = _nodes.asStateFlow()

    /**
     * True while the stored vault exists but its last read failed for a reason
     * that may pass. Reads then serve the last good copy (or nothing, before
     * the first good read) and writes are refused; [retryRead] reads again.
     */
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    init {
        scope.launch {
            // Keep the last good document on an unreadable read; a passing fault must not empty the node list.
            store.data.map { decode(it[DOCUMENT_KEY]) }.collect(::adopt)
        }
    }

    /** Reads the stored vault again (a passing Keystore fault may have cleared); true when it reads now. */
    suspend fun retryRead(): Boolean = adopt(readStored()) !is Read.Unreadable

    /**
     * Throws [VaultUnreadableException] when reads would serve an empty stand-in
     * for a vault that exists but cannot be read: no good copy is held and the
     * stored document does not read now. A null from a read after this returns
     * means the secret is really absent.
     */
    suspend fun requireReadable() {
        if (document.value != null) return
        val read = adopt(readStored())
        if (read is Read.Unreadable) throw VaultUnreadableException(read.cause)
    }

    /**
     * Throws [VaultUnreadableException] when a write would be refused now. Call it
     * before an action that cannot be repeated (claiming a node), so a vault fault
     * stops the action instead of losing the secret it returns.
     */
    suspend fun requireWritable() {
        val read = adopt(readStored())
        if (read is Read.Unreadable) throw VaultUnreadableException(read.cause)
    }

    /**
     * The API key for [id], or null when the node is not paired. Reads memory
     * after the first load; the first call may block on disk once, so call it
     * off the main thread (OkHttp interceptors and IO coroutines are fine).
     */
    fun apiKey(id: DeviceId): String? = current().nodes[id.value]?.apiKey

    /** The stored record for [id], or null. Same threading rule as [apiKey]. */
    fun node(id: DeviceId): NodeCredential? = current().nodes[id.value]

    /** Insert or replace a node record (keyed by device id). */
    suspend fun putNode(credential: NodeCredential) = mutate {
        it.copy(nodes = it.nodes + (credential.deviceId to credential))
    }

    /**
     * Update the reach hints of a stored node; no-op for an unknown node. A
     * best-effort hint written automatically on discovery changes: while the
     * vault is unreadable it is skipped rather than thrown.
     */
    suspend fun updateAddress(id: DeviceId, lastIp: String?, lastPort: Int?, mdnsHost: String? = null) = try {
        mutate { doc ->
            val existing = doc.nodes[id.value] ?: return@mutate doc
            val updated = existing.copy(
                lastIp = lastIp ?: existing.lastIp,
                lastPort = lastPort ?: existing.lastPort,
                mdnsHost = mdnsHost ?: existing.mdnsHost,
            )
            if (updated == existing) doc else doc.copy(nodes = doc.nodes + (id.value to updated))
        }
    } catch (_: VaultUnreadableException) {
        Unit
    }

    suspend fun removeNode(id: DeviceId) = mutate { it.copy(nodes = it.nodes - id.value) }

    /** Sealed phone-radio invite material, or null when none is stored. */
    suspend fun wfbInvite(): ByteArray? = load().wfbInvite?.let { Base64.getDecoder().decode(it) }

    suspend fun putWfbInvite(blob: ByteArray?) = mutate {
        it.copy(wfbInvite = blob?.let { b -> Base64.getEncoder().encodeToString(b) })
    }

    /** The MAVLink v2 signing key (32 bytes) used on links to [id], or null. */
    suspend fun mavlinkSigningKey(id: DeviceId): ByteArray? =
        load().mavlinkSigningKeys[id.value]?.let { Base64.getDecoder().decode(it) }

    /** Store or (with null) clear the MAVLink signing key for [id]. */
    suspend fun putMavlinkSigningKey(id: DeviceId, key: ByteArray?) = mutate {
        require(key == null || key.size == SIGNING_KEY_BYTES) { "signing key must be $SIGNING_KEY_BYTES bytes" }
        val keys = if (key == null) it.mavlinkSigningKeys - id.value else it.mavlinkSigningKeys + (id.value to Base64.getEncoder().encodeToString(key))
        it.copy(mavlinkSigningKeys = keys)
    }

    suspend fun cloudRefreshToken(): String? = load().cloudRefreshToken

    suspend fun putCloudRefreshToken(token: String?) = mutate { it.copy(cloudRefreshToken = token) }

    /**
     * A random identity minted once per install, presented as `user_id` when
     * claiming a node while not signed in to the cloud. Random rather than
     * derived from a hardware id because the node stores it in its pairing record.
     */
    suspend fun installId(): String {
        load().installId?.let { return it }
        var minted = ""
        mutate { doc ->
            minted = doc.installId ?: ("android-" + UUID.randomUUID().toString())
            if (doc.installId != null) doc else doc.copy(installId = minted)
        }
        return minted
    }

    /** Remove every secret. */
    suspend fun wipe() = mutate { VaultDocument(installId = it.installId) }

    private fun current(): VaultDocument =
        document.value ?: runBlocking { load() }

    /** The document for reads: the last good one, or empty (not cached) while the store is unreadable. */
    private suspend fun load(): VaultDocument {
        document.value?.let { return it }
        return when (val read = adopt(readStored())) {
            is Read.Ok -> read.doc
            Read.Absent, is Read.KeyLost -> VaultDocument()
            is Read.Unreadable -> VaultDocument()
        }
    }

    private suspend fun readStored(): Read = decode(store.data.first()[DOCUMENT_KEY])

    /** Records whether [read] succeeded and publishes what it found; an unreadable read keeps the last good copy. */
    private fun adopt(read: Read): Read {
        _unreadable.value = read is Read.Unreadable
        when (read) {
            is Read.Ok -> publish(read.doc)
            Read.Absent, is Read.KeyLost -> publish(VaultDocument())
            is Read.Unreadable -> Unit
        }
        return read
    }

    private suspend fun mutate(change: (VaultDocument) -> VaultDocument) {
        writeLock.withLock {
            val stored = store.data.first()[DOCUMENT_KEY]
            val read = decode(stored)
            _unreadable.value = read is Read.Unreadable
            val before = when (read) {
                is Read.Ok -> read.doc
                Read.Absent, is Read.KeyLost -> VaultDocument()
                is Read.Unreadable -> throw VaultUnreadableException(read.cause)
            }
            val after = change(before)
            if (after == before) return
            store.edit {
                // The ciphertext of a lost key is kept aside, never overwritten in place.
                if (read is Read.KeyLost && stored != null) it[LOST_DOCUMENT_KEY] = stored
                it[DOCUMENT_KEY] = encode(after)
            }
            publish(after)
        }
    }

    private fun publish(doc: VaultDocument) {
        document.value = doc
        _nodes.value = doc.nodes.values.sortedWith(compareBy({ it.name ?: "" }, { it.deviceId }))
    }

    private fun encode(doc: VaultDocument): String {
        val plain = json.encodeToString(VaultDocument.serializer(), doc).encodeToByteArray()
        return Base64.getEncoder().encodeToString(cipher.seal(plain))
    }

    private sealed interface Read {
        data object Absent : Read
        class Ok(val doc: VaultDocument) : Read
        class KeyLost(val cause: Throwable) : Read
        class Unreadable(val cause: Throwable) : Read
    }

    private fun decode(stored: String?): Read {
        if (stored.isNullOrEmpty()) return Read.Absent
        val plain = try {
            cipher.open(Base64.getDecoder().decode(stored))
        } catch (e: Exception) {
            return if (isKeyLoss(e)) Read.KeyLost(e) else Read.Unreadable(e)
        }
        return try {
            Read.Ok(json.decodeFromString(VaultDocument.serializer(), plain.decodeToString()))
        } catch (e: Exception) {
            Read.Unreadable(e)
        }
    }

    companion object {
        const val SIGNING_KEY_BYTES = 32
        internal val DOCUMENT_KEY = stringPreferencesKey("vault_document_v1")

        /** The last document whose key was lost, kept rather than destroyed. */
        internal val LOST_DOCUMENT_KEY = stringPreferencesKey("vault_document_v1_lost")
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /**
         * Failures that mean the key behind the document is gone for good: GCM
         * authentication fails (the document was sealed under another key), or
         * the Keystore says the key cannot be recovered or was invalidated.
         */
        internal fun isKeyLoss(e: Throwable): Boolean {
            var t: Throwable? = e
            while (t != null) {
                if (t is AEADBadTagException || t is UnrecoverableKeyException || t is KeyPermanentlyInvalidatedException) return true
                t = t.cause
            }
            return false
        }
    }
}
