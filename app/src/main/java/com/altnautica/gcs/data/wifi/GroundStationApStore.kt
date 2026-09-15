package com.altnautica.gcs.data.wifi

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The operator-supplied ground-station AP credential for this unit.
 *
 * Stored because the passphrase is generated per unit and only shown on the
 * node (installer summary, status page, on-box panel QR) — an operator cannot
 * be asked to retype it on every app start. Absent until they enter it, and
 * absence is a prompt, never a fallback to a guessed value.
 */
@Singleton
class GroundStationApStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    companion object {
        val AP_PASSPHRASE = stringPreferencesKey("ground_station_ap_passphrase")
        val AP_SSID = stringPreferencesKey("ground_station_ap_ssid")
    }

    val passphrase: Flow<String?> =
        dataStore.data.map { it[AP_PASSPHRASE]?.takeIf(String::isNotBlank) }

    /** The full SSID when the operator scanned a QR that carried one. */
    val ssid: Flow<String?> = dataStore.data.map { it[AP_SSID]?.takeIf(String::isNotBlank) }

    suspend fun current(): GroundStationCredential? {
        val prefs = dataStore.data.first()
        val pass = prefs[AP_PASSPHRASE]?.takeIf(String::isNotBlank) ?: return null
        return GroundStationCredential(
            passphrase = pass,
            ssid = prefs[AP_SSID]?.takeIf(String::isNotBlank),
        )
    }

    suspend fun store(credential: GroundStationCredential) {
        dataStore.edit { prefs ->
            prefs[AP_PASSPHRASE] = credential.passphrase
            credential.ssid?.let { prefs[AP_SSID] = it }
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(AP_PASSPHRASE)
            prefs.remove(AP_SSID)
        }
    }
}
