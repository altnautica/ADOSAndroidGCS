package com.altnautica.gcs.core.data.vault

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
import java.util.Base64
import java.util.UUID

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
 * Encrypted store for every secret the app holds: per-node agent API keys,
 * the phone-radio invite material, and the cloud refresh token.
 *
 * The plaintext is one JSON document sealed with [CipherEngine] (an AES-256-GCM
 * key in the Android Keystore in production) and kept as base64 in a dedicated
 * DataStore file. A document that no longer decrypts (key lost after a backup
 * restore onto another device) is treated as empty: the operator re-pairs,
 * which is the only safe recovery for a secret that cannot be read.
 */
class CredentialVault(
    private val store: DataStore<Preferences>,
    private val cipher: CipherEngine,
    scope: CoroutineScope,
) {

    private val writeLock = Mutex()
    private val document = MutableStateFlow<VaultDocument?>(null)
    private val _nodes = MutableStateFlow<List<NodeCredential>>(emptyList())

    /** Every paired node, ordered by name then id. */
    val nodes: StateFlow<List<NodeCredential>> = _nodes.asStateFlow()

    init {
        scope.launch {
            store.data.map { decode(it[DOCUMENT_KEY]) }.collect(::publish)
        }
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

    /** Update the reach hints of a stored node; no-op for an unknown node. */
    suspend fun updateAddress(id: DeviceId, lastIp: String?, lastPort: Int?, mdnsHost: String? = null) = mutate { doc ->
        val existing = doc.nodes[id.value] ?: return@mutate doc
        val updated = existing.copy(
            lastIp = lastIp ?: existing.lastIp,
            lastPort = lastPort ?: existing.lastPort,
            mdnsHost = mdnsHost ?: existing.mdnsHost,
        )
        if (updated == existing) doc else doc.copy(nodes = doc.nodes + (id.value to updated))
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

    private suspend fun load(): VaultDocument {
        document.value?.let { return it }
        val doc = decode(store.data.first()[DOCUMENT_KEY])
        publish(doc)
        return doc
    }

    private suspend fun mutate(change: (VaultDocument) -> VaultDocument) {
        writeLock.withLock {
            val before = decode(store.data.first()[DOCUMENT_KEY])
            val after = change(before)
            if (after == before) return
            store.edit { it[DOCUMENT_KEY] = encode(after) }
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

    private fun decode(stored: String?): VaultDocument {
        if (stored.isNullOrEmpty()) return VaultDocument()
        return try {
            val plain = cipher.open(Base64.getDecoder().decode(stored))
            json.decodeFromString(VaultDocument.serializer(), plain.decodeToString())
        } catch (_: Exception) {
            VaultDocument()
        }
    }

    companion object {
        const val SIGNING_KEY_BYTES = 32
        internal val DOCUMENT_KEY = stringPreferencesKey("vault_document_v1")
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}
