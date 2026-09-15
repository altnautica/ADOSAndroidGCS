package com.altnautica.gcs.data.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Joins and binds the ground-station Wi-Fi AP.
 *
 * **There is no default passphrase.** The agent generates the AP passphrase per
 * unit on first boot, so a constant compiled in here could only ever be one of
 * two defects: a published credential for every unit that happened to use it,
 * or an association failure on every unit that did not. The passphrase is a
 * required argument, supplied from operator input or from the WPA join string
 * the node's on-box panel renders as a QR code.
 */
@Singleton
class WifiConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "WifiConnectionManager"

        /** SSID prefix the agent builds every ground-station AP name from. */
        const val GS_SSID_PREFIX = "ADOS-GS-"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var boundNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _ssid = MutableStateFlow<String?>(null)
    val ssid: StateFlow<String?> = _ssid.asStateFlow()

    /**
     * Check if currently connected to an ADOS ground station WiFi network.
     */
    @Suppress("DEPRECATION")
    fun isConnectedToGroundStation(): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false
        val info = wifiManager.connectionInfo ?: return false
        val currentSsid = info.ssid?.removeSurrounding("\"") ?: return false
        return currentSsid.startsWith(GS_SSID_PREFIX)
    }

    /**
     * Request connection to an ADOS ground station network and bind the process
     * to it, so HTTP traffic routes over the ground station even when mobile
     * data is available.
     *
     * [passphrase] must be the passphrase for this specific unit. A blank value
     * is refused rather than attempted: an empty WPA2 passphrase produces an
     * association failure the operator reads as broken hardware.
     *
     * @return true when the join request was submitted.
     */
    fun requestGroundStationNetwork(
        passphrase: String,
        ssidSuffix: String = "",
    ): Boolean {
        if (passphrase.isBlank()) {
            Log.w(TAG, "refusing to join $GS_SSID_PREFIX* with a blank passphrase")
            return false
        }
        releaseNetwork()

        val ssidPattern = "$GS_SSID_PREFIX$ssidSuffix"

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(android.os.PatternMatcher(ssidPattern, android.os.PatternMatcher.PATTERN_PREFIX))
            .setWpa2Passphrase(passphrase)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Ground station network available")
                boundNetwork = network
                // Pin all socket traffic to this network so API calls go to the AP
                connectivityManager.bindProcessToNetwork(network)
                _connected.value = true
                _ssid.value = ssidPattern
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Ground station network lost")
                if (boundNetwork == network) {
                    connectivityManager.bindProcessToNetwork(null)
                    boundNetwork = null
                }
                _connected.value = false
                _ssid.value = null
            }

            override fun onUnavailable() {
                Log.w(TAG, "Ground station network unavailable")
                _connected.value = false
            }
        }

        networkCallback = callback
        connectivityManager.requestNetwork(request, callback)
        Log.i(TAG, "Requested ground station network: $ssidPattern")
        return true
    }

    /**
     * Release the bound network and stop requesting ground station WiFi.
     */
    fun releaseNetwork() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: IllegalArgumentException) {
                // Already unregistered
            }
        }
        networkCallback = null

        if (boundNetwork != null) {
            connectivityManager.bindProcessToNetwork(null)
            boundNetwork = null
        }

        _connected.value = false
        _ssid.value = null
    }
}
