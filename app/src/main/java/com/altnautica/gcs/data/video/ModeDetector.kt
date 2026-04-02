package com.altnautica.gcs.data.video

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.altnautica.gcs.data.serial.UsbSerialManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModeDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usbSerialManager: UsbSerialManager,
) {

    companion object {
        private const val TAG = "ModeDetector"

        // RTL8812EU USB identifiers
        private const val RTL8812EU_VENDOR_ID = 0x0BDA
        private const val RTL8812EU_PRODUCT_ID = 0x8812

        // Alternative RTL8812AU identifiers
        private const val RTL8812AU_PRODUCT_ID = 0x8812

        private const val GS_SSID_PREFIX = "ADOS-GS-"
        private const val GS_BASE_URL = "http://192.168.4.1:8080"
        private const val CLOUD_RELAY_URL = "turn:turn.altnautica.com:3478"
    }

    fun detect(): VideoMode {
        // Priority 1: Direct USB WFB-ng adapter
        if (isUsbAdapterConnected()) {
            Log.d(TAG, "Mode B: Direct USB WFB-ng adapter detected")
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = usbManager.deviceList.values.firstOrNull { isWfbAdapter(it.vendorId, it.productId) }
            return VideoMode.DirectUsb(device?.deviceId ?: 0)
        }

        // Priority 2: Ground station WiFi AP
        if (isGroundStationWifi()) {
            Log.d(TAG, "Mode A: Ground station WiFi detected")
            return VideoMode.GroundStation("$GS_BASE_URL/whep")
        }

        // Priority 3: Internet available for cloud relay
        if (hasInternetConnection()) {
            Log.d(TAG, "Mode C: Cloud relay fallback")
            return VideoMode.CloudRelay(CLOUD_RELAY_URL)
        }

        Log.d(TAG, "No video connection available")
        return VideoMode.NoConnection
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
}
