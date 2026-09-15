package com.altnautica.gcs.data.pairing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.altnautica.gcs.data.settings.CachedPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted agent pairing credential and the identity this install claims with.
 *
 * Split out from [PairingRepository] because the OkHttp auth interceptor needs
 * the key and the repository needs the HTTP client: one type holding both would
 * be a dependency cycle. This one depends on nothing but DataStore.
 *
 * The key is the agent's full-authority credential, so it is never logged and
 * never placed in a URL — the interceptor puts it in the `X-ADOS-Key` header,
 * which is the only channel the agent's LAN edge reads it from.
 */
@Singleton
class AgentCredentialStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    companion object {
        val API_KEY = stringPreferencesKey("ground_station_api_key")
        val PAIRED_DEVICE_ID = stringPreferencesKey("ground_station_device_id")
        val PAIRED_DEVICE_NAME = stringPreferencesKey("ground_station_device_name")
        val PAIRED_MDNS_HOST = stringPreferencesKey("ground_station_mdns_host")
        val OPERATOR_ID = stringPreferencesKey("operator_id")

        /** Bytes of entropy behind a generated operator id. */
        private const val OPERATOR_ID_BYTES = 8
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val apiKey: Flow<String?> = dataStore.data.map { it[API_KEY]?.takeIf(String::isNotBlank) }

    /** Device id of the node this install is paired with, when known. */
    val pairedDeviceId: Flow<String?> =
        dataStore.data.map { it[PAIRED_DEVICE_ID]?.takeIf(String::isNotBlank) }

    val pairedDeviceName: Flow<String?> =
        dataStore.data.map { it[PAIRED_DEVICE_NAME]?.takeIf(String::isNotBlank) }

    /**
     * The reach the agent reported at claim time. The agent returns a name it
     * has proven resolvable, so this is offered to the operator as a base-URL
     * suggestion rather than silently adopted.
     */
    val pairedMdnsHost: Flow<String?> =
        dataStore.data.map { it[PAIRED_MDNS_HOST]?.takeIf(String::isNotBlank) }

    private val cachedApiKey = CachedPreference(apiKey, scope)
    private val cachedPairedDeviceId = CachedPreference(pairedDeviceId, scope)

    /** The key for the auth interceptor; null while this device is unpaired. */
    fun currentApiKey(): String? = cachedApiKey.current()

    /** The device id for the cloud-relay subscription; null while unpaired. */
    fun currentPairedDeviceId(): String? = cachedPairedDeviceId.current()

    suspend fun store(claim: ClaimResponse) {
        dataStore.edit { prefs ->
            prefs[API_KEY] = claim.apiKey
            prefs[PAIRED_DEVICE_ID] = claim.deviceId
            prefs[PAIRED_DEVICE_NAME] = claim.name
            prefs[PAIRED_MDNS_HOST] = claim.mdnsHost
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(API_KEY)
            prefs.remove(PAIRED_DEVICE_ID)
            prefs.remove(PAIRED_DEVICE_NAME)
            prefs.remove(PAIRED_MDNS_HOST)
        }
    }

    /**
     * The owner identity this install presents on `POST /api/pairing/claim`,
     * generated once and then stable so a re-pair after an unpair is recognised
     * as the same operator. Random rather than derived from any device
     * identifier: the value is written into the node's pairing record, which is
     * readable by anything holding the key.
     */
    suspend fun operatorId(): String {
        val existing = dataStore.data.first()[OPERATOR_ID]
        if (!existing.isNullOrBlank()) return existing
        val minted = "android-" + randomHex(OPERATOR_ID_BYTES)
        dataStore.edit { it[OPERATOR_ID] = minted }
        return minted
    }

    private fun randomHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }
}
