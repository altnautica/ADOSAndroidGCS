package com.altnautica.gcs.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes the ground station base URL from DataStore preferences.
 *
 * The base URL is the HTTP root for the ground station REST API, e.g.
 * "http://192.168.4.1:8080/". The MAVLink WebSocket URL is derived from
 * this by swapping the scheme to ws:// and appending the canonical path.
 */
@Singleton
class BaseUrlProvider @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    companion object {
        const val DEFAULT_BASE_URL = "http://192.168.4.1:8080/"
        private const val MAVLINK_WS_PATH = "api/v1/ground-station/ws/mavlink"
        val BASE_URL_KEY = stringPreferencesKey("ground_station_base_url")

        /**
         * Validates that [url] is a syntactically acceptable base URL.
         * Must be http:// or https://, with a host, and end with "/".
         */
        fun isValidBaseUrl(url: String): Boolean {
            if (!url.endsWith("/")) return false
            return try {
                val parsed = java.net.URL(url)
                (parsed.protocol == "http" || parsed.protocol == "https") &&
                    !parsed.host.isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Convert an http(s) base URL into the MAVLink WebSocket URL.
         */
        fun toMavlinkWsUrl(baseUrl: String): String {
            val normalised = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val wsBase = when {
                normalised.startsWith("https://") -> normalised.replaceFirst("https://", "wss://")
                normalised.startsWith("http://") -> normalised.replaceFirst("http://", "ws://")
                else -> normalised
            }
            return wsBase + MAVLINK_WS_PATH
        }
    }

    val baseUrl: Flow<String> = dataStore.data
        .map { prefs -> prefs[BASE_URL_KEY] ?: DEFAULT_BASE_URL }

    /**
     * Synchronously read the current base URL. Used at Hilt provider
     * construction time where suspending APIs are not available.
     */
    fun getBaseUrlBlocking(): String = runBlocking {
        baseUrl.first()
    }

    suspend fun setBaseUrl(url: String): Boolean {
        if (!isValidBaseUrl(url)) return false
        dataStore.edit { it[BASE_URL_KEY] = url }
        return true
    }
}
