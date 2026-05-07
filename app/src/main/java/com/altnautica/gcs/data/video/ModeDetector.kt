package com.altnautica.gcs.data.video

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.altnautica.gcs.data.discovery.NsdAgentDiscovery
import com.altnautica.gcs.data.serial.UsbSerialManager
import com.altnautica.gcs.data.settings.BaseUrlProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModeDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usbSerialManager: UsbSerialManager,
    private val nsdAgentDiscovery: NsdAgentDiscovery,
    private val baseUrlProvider: BaseUrlProvider,
) {

    companion object {
        private const val TAG = "ModeDetector"

        // RTL8812EU USB identifiers
        private const val RTL8812EU_VENDOR_ID = 0x0BDA
        private const val RTL8812EU_PRODUCT_ID = 0x8812

        // Alternative RTL8812AU identifiers
        private const val RTL8812AU_PRODUCT_ID = 0x8812

        private const val GS_SSID_PREFIX = "ADOS-GS-"
        private const val GS_DEFAULT_HOST = "192.168.4.1"
        private const val GS_DEFAULT_PORT = 8080
        private const val GS_BASE_URL = "http://$GS_DEFAULT_HOST:$GS_DEFAULT_PORT"

        // Agent USB gadget mode default subnet. The agent's CDC-NCM /
        // RNDIS gadget brings up usb0 with a DHCP server handing out
        // 192.168.7.0/24 leases and binds the REST/WS surface to
        // 192.168.7.1.
        internal const val USB_TETHER_SUBNET_PREFIX = "192.168.7."
        internal const val USB_TETHER_HOST = "192.168.7.1"
        internal const val USB_TETHER_PORT = 8080
        internal const val USB_TETHER_BASE_URL = "http://$USB_TETHER_HOST:$USB_TETHER_PORT"

        private const val CLOUD_RELAY_URL = "turn:turn.altnautica.com:3478"

        /** Returns true when [address] is inside the agent USB-gadget subnet. */
        internal fun isInUsbTetherSubnet(address: String?): Boolean {
            if (address.isNullOrBlank()) return false
            return address.startsWith(USB_TETHER_SUBNET_PREFIX)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun detect(): VideoMode {
        // Priority 1: Direct USB WFB-ng adapter
        if (isUsbAdapterConnected()) {
            Log.d(TAG, "Mode B: Direct USB WFB-ng adapter detected")
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = usbManager.deviceList.values.firstOrNull { isWfbAdapter(it.vendorId, it.productId) }
            return VideoMode.DirectUsb(device?.deviceId ?: 0)
        }

        // Priority 2: USB-C tether to the agent over CDC-NCM / RNDIS.
        // Lower latency than WiFi AP and survives RF outages, so it wins
        // over WiFi when both are present.
        if (isUsbTetherConnected()) {
            val whepUrl = "$USB_TETHER_BASE_URL/whep"
            Log.d(TAG, "Mode A: USB-C tether detected whep=$whepUrl")
            persistBaseUrl("$USB_TETHER_BASE_URL/")
            return VideoMode.GroundStation(whepUrl)
        }

        // Priority 3: Ground station WiFi AP. Use the most recently
        // resolved NSD endpoint when available; fall back to the
        // hardcoded AP IP otherwise. The async discovery is kicked off
        // by detectSuspending() so non-suspending callers still pick
        // up a discovered host as soon as one round has completed.
        if (isGroundStationWifi()) {
            val whepUrl = nsdAgentDiscovery.lastResolved.value
                ?.let { "http://${it.host}:${it.port}/whep" }
                ?: "$GS_BASE_URL/whep"
            Log.d(TAG, "Mode A: Ground station WiFi detected whep=$whepUrl")
            return VideoMode.GroundStation(whepUrl)
        }

        // Priority 4: Internet available for cloud relay
        if (hasInternetConnection()) {
            Log.d(TAG, "Mode C: Cloud relay fallback")
            return VideoMode.CloudRelay(CLOUD_RELAY_URL)
        }

        Log.d(TAG, "No video connection available")
        return VideoMode.NoConnection
    }

    /**
     * Same as [detect] but kicks off an mDNS lookup before the
     * GroundStation fallback so the discovered host beats the
     * hardcoded AP IP. 3-second timeout. Falls back to
     * `192.168.4.1:8080` on timeout or NSD failure.
     */
    suspend fun detectSuspending(): VideoMode {
        if (isUsbAdapterConnected()) {
            return detect()
        }
        if (isUsbTetherConnected()) {
            return detect()
        }
        if (isGroundStationWifi()) {
            val resolved = nsdAgentDiscovery.discover()
            val whepUrl = resolved
                ?.let { "http://${it.host}:${it.port}/whep" }
                ?: "$GS_BASE_URL/whep"
            Log.d(
                TAG,
                "Mode A via ${if (resolved != null) "NSD" else "fallback"} whep=$whepUrl",
            )
            return VideoMode.GroundStation(whepUrl)
        }
        return detect()
    }

    /**
     * Detect the full connection mode, including Mode D (USB serial to FC).
     * Mode D provides MAVLink telemetry only, no video stream.
     */
    fun detectConnectionMode(): ConnectionMode {
        val videoMode = detect()

        // If no video mode is available, check for USB serial FC (Mode D)
        if (videoMode is VideoMode.NoConnection) {
            if (usbSerialManager.isConnected.value) {
                val deviceName = usbSerialManager.connectedDevice.value ?: "USB FC"
                Log.d(TAG, "Mode D: USB serial FC connected ($deviceName)")
                return ConnectionMode.DirectSerial(deviceName)
            }
            if (usbSerialManager.hasConnectedFc()) {
                Log.d(TAG, "Mode D: USB serial FC detected (not connected yet)")
                return ConnectionMode.DirectSerial("FC detected")
            }
        }

        return ConnectionMode.WebSocket(videoMode)
    }

    private fun isUsbAdapterConnected(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return usbManager.deviceList.values.any { device ->
            isWfbAdapter(device.vendorId, device.productId)
        }
    }

    private fun isWfbAdapter(vendorId: Int, productId: Int): Boolean {
        return vendorId == RTL8812EU_VENDOR_ID &&
            (productId == RTL8812EU_PRODUCT_ID || productId == RTL8812AU_PRODUCT_ID)
    }

    @Suppress("DEPRECATION")
    private fun isGroundStationWifi(): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false
        val info = wifiManager.connectionInfo ?: return false
        val ssid = info.ssid?.removeSurrounding("\"") ?: return false
        return ssid.startsWith(GS_SSID_PREFIX)
    }

    private fun hasInternetConnection(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Probe ConnectivityManager for a USB-tethered ethernet (or USB)
     * transport whose link addresses fall inside the agent's USB-gadget
     * subnet (192.168.7.0/24). Walks every reachable Network so the check
     * works even when the active network is the cellular modem or WiFi.
     */
    internal fun isUsbTetherConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val networks = try {
            cm.allNetworks
        } catch (t: Throwable) {
            Log.w(TAG, "allNetworks_failed ${t.message}")
            return false
        }
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val isUsb = try {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)
            } catch (_: Throwable) {
                // TRANSPORT_USB is API 31+; older devices throw via the
                // hidden constant. Fall back to ethernet transport only.
                false
            }
            if (!isEthernet && !isUsb) continue
            val link = cm.getLinkProperties(network) ?: continue
            if (hasUsbTetherAddress(link.linkAddresses)) {
                return true
            }
        }
        return false
    }

    private fun hasUsbTetherAddress(addresses: List<LinkAddress>): Boolean {
        for (la in addresses) {
            val host = la.address?.hostAddress ?: continue
            if (isInUsbTetherSubnet(host)) return true
        }
        return false
    }

    private fun persistBaseUrl(url: String) {
        scope.launch {
            try {
                baseUrlProvider.setBaseUrl(url)
            } catch (t: Throwable) {
                Log.w(TAG, "set_base_url_failed ${t.message}")
            }
        }
    }
}
