package com.altnautica.gcs.core.link.direct

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.altnautica.gcs.core.link.transport.UsbSerialTransport
import com.altnautica.gcs.core.model.DeviceId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A manually configured network endpoint for a flight controller. */
sealed interface DirectEndpoint {
    val nodeId: DeviceId
    val label: String

    /** Listen for MAVLink datagrams on [listenPort]; replies go to the last sender. */
    data class Udp(val listenPort: Int = DEFAULT_UDP_PORT) : DirectEndpoint {
        override val nodeId: DeviceId get() = DeviceId("udp:$listenPort")
        override val label: String get() = "UDP :$listenPort"
    }

    /** Connect to a MAVLink TCP server. */
    data class Tcp(val host: String, val port: Int = DEFAULT_TCP_PORT) : DirectEndpoint {
        override val nodeId: DeviceId get() = DeviceId("tcp:$host:$port")
        override val label: String get() = "TCP $host:$port"
    }

    companion object {
        const val DEFAULT_UDP_PORT = 14_550
        const val DEFAULT_TCP_PORT = 5_760

        internal fun encode(e: DirectEndpoint): String = when (e) {
            is Udp -> "udp:${e.listenPort}"
            is Tcp -> "tcp:${e.host}:${e.port}"
        }

        /** Parses a node id or stored value (`udp:14550`, `tcp:host:5760`). */
        fun parse(value: String): DirectEndpoint? {
            val parts = value.split(':')
            return when {
                parts.size == 2 && parts[0] == "udp" -> parts[1].toIntOrNull()?.takeIf { it in 1..65535 }?.let { Udp(it) }
                parts.size >= 3 && parts[0] == "tcp" -> {
                    val port = parts.last().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                    val host = parts.subList(1, parts.size - 1).joinToString(":")
                    if (host.isBlank()) null else Tcp(host, port)
                }
                else -> null
            }
        }
    }
}

/**
 * Operator settings for direct flight-controller links: manual UDP/TCP
 * endpoints, the demo vehicle toggle and the USB serial baud rate.
 */
@Singleton
class DirectLinkSettings @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val endpoints: Flow<List<DirectEndpoint>> = dataStore.data
        .map { prefs -> prefs[ENDPOINTS].orEmpty().mapNotNull(DirectEndpoint::parse).sortedBy { it.label } }
        .distinctUntilChanged()

    val demoEnabled: Flow<Boolean> = dataStore.data.map { it[DEMO] ?: false }.distinctUntilChanged()

    val usbBaudRate: Flow<Int> = dataStore.data
        .map { it[USB_BAUD] ?: UsbSerialTransport.DEFAULT_BAUD }
        .distinctUntilChanged()

    suspend fun addEndpoint(endpoint: DirectEndpoint) {
        dataStore.edit { it[ENDPOINTS] = it[ENDPOINTS].orEmpty() + DirectEndpoint.encode(endpoint) }
    }

    suspend fun removeEndpoint(endpoint: DirectEndpoint) {
        dataStore.edit { it[ENDPOINTS] = it[ENDPOINTS].orEmpty() - DirectEndpoint.encode(endpoint) }
    }

    suspend fun setDemoEnabled(enabled: Boolean) {
        dataStore.edit { it[DEMO] = enabled }
    }

    /** One of [UsbSerialTransport.BAUD_RATES]. */
    suspend fun setUsbBaudRate(baud: Int) {
        require(baud in UsbSerialTransport.BAUD_RATES) { "Unsupported baud rate $baud" }
        dataStore.edit { it[USB_BAUD] = baud }
    }

    private companion object {
        val ENDPOINTS = stringSetPreferencesKey("direct_link_endpoints")
        val DEMO = booleanPreferencesKey("direct_link_demo_enabled")
        val USB_BAUD = intPreferencesKey("direct_link_usb_baud")
    }
}
