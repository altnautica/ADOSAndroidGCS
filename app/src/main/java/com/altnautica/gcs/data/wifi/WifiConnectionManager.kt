package com.altnautica.gcs.data.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "WifiConnectionManager"
        private const val GS_SSID_PREFIX = "ADOS-GS-"
        private const val GS_DEFAULT_PASSWORD = "adosground"
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
     * Request connection to an ADOS ground station network.
     * Uses WifiNetworkSpecifier (Android 10+) to connect and bind the process
     * so HTTP traffic routes through the ground station WiFi even if mobile data is available.
     */
    fun requestGroundStationNetwork(ssidSuffix: String = "", password: String = GS_DEFAULT_PASSWORD) {
        releaseNetwork()

        val ssidPattern = "$GS_SSID_PREFIX$ssidSuffix"

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(android.os.PatternMatcher(ssidPattern, android.os.PatternMatcher.PATTERN_PREFIX))
            .setWpa2Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Ground station network available")
                boundNetwork = network
                // Pin all socket traffic to this network so API calls go to 192.168.4.1
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
