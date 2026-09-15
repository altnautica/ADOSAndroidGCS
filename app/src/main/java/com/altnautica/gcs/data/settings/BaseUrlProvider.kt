package com.altnautica.gcs.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes the agent base URL from DataStore preferences.
 *
 * The base URL is the HTTP root for the agent's control surface, e.g.
 * `http://192.168.4.1:8080/`. The MAVLink WebSocket URL is derived from its
 * host: the agent serves raw MAVLink on a separate listener, not as a path
 * under the control surface.
 */
@Singleton
class BaseUrlProvider @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    companion object {
        const val DEFAULT_BASE_URL = "http://192.168.4.1:8080/"

        /** Port the agent's HTTP control surface listens on. */
        const val AGENT_CONTROL_PORT = 8080

        /**
         * The agent's MAVLink WebSocket listener port.
         *
         * The proxy is owned by the agent's MAVLink router and binds its own
         * port at the host root — it is not a path on the control surface. The
         * path this client used to dial
         * (`/api/v1/ground-station/ws/mavlink`, on the control port) is served
         * by nothing: every handshake 404'd, which left every MAVLink-backed
         * screen dark.
         */
        const val MAVLINK_WS_PORT = 8765

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
         * Convert an http(s) base URL into the MAVLink WebSocket URL: the same
         * host, the MAVLink listener's port, and the root path.
         *
         * The control-surface port in [baseUrl] is deliberately discarded —
         * only the host carries over.
         */
        fun toMavlinkWsUrl(baseUrl: String): String {
            val scheme = if (baseUrl.startsWith("https://")) "wss" else "ws"
            val host = try {
                java.net.URL(baseUrl).host
            } catch (_: Exception) {
                null
            }
            if (host.isNullOrBlank()) {
                return "$scheme://$baseUrl"
            }
            val bracketed = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            return "$scheme://$bracketed:$MAVLINK_WS_PORT/"
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val baseUrl: Flow<String> = dataStore.data
        .map { prefs -> prefs[BASE_URL_KEY] ?: DEFAULT_BASE_URL }

    private val cached = CachedPreference(baseUrl, scope)

    /**
     * The base URL as configured right now, for the OkHttp interceptor that
     * retargets each request. Not a construction-time snapshot: an address the
     * operator edits in Settings takes effect on the next call.
     */
    fun currentBaseUrl(): String = cached.current()

    suspend fun setBaseUrl(url: String): Boolean {
        if (!isValidBaseUrl(url)) return false
        dataStore.edit { it[BASE_URL_KEY] = url }
        return true
    }
}
