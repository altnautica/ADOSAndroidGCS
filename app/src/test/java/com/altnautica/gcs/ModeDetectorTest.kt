package com.altnautica.gcs

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.altnautica.gcs.data.video.ModeDetector
import com.altnautica.gcs.data.video.VideoMode
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModeDetectorTest {

    private lateinit var context: Context
    private lateinit var usbManager: UsbManager
    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var detector: ModeDetector

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        usbManager = mockk(relaxed = true)
        wifiManager = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)

        every { context.getSystemService(Context.USB_SERVICE) } returns usbManager
        every { context.getSystemService(Context.WIFI_SERVICE) } returns wifiManager
        every { context.applicationContext.getSystemService(Context.WIFI_SERVICE) } returns wifiManager
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager

        detector = ModeDetector(context)
    }

    @Test
    fun `USB adapter detected returns DirectUsb mode`() {
        val device = mockk<UsbDevice>()
        every { device.vendorId } returns 0x0BDA
        every { device.productId } returns 0x8812
        every { device.deviceId } returns 42
        every { usbManager.deviceList } returns hashMapOf("dev1" to device)

        val mode = detector.detect()
        assertTrue("Expected DirectUsb but got $mode", mode is VideoMode.DirectUsb)
        assertTrue((mode as VideoMode.DirectUsb).deviceId == 42)
    }

    @Test
    fun `ground station WiFi returns GroundStation mode when no USB`() {
        // No USB adapter
        every { usbManager.deviceList } returns hashMapOf()

        // Ground station WiFi connected
        val wifiInfo = mockk<WifiInfo>()
        every { wifiInfo.ssid } returns "\"ADOS-GS-ABC123\""
        @Suppress("DEPRECATION")
        every { wifiManager.connectionInfo } returns wifiInfo

        val mode = detector.detect()
        assertTrue("Expected GroundStation but got $mode", mode is VideoMode.GroundStation)
    }

    @Test
    fun `internet available returns CloudRelay when no USB and no GS WiFi`() {
        // No USB adapter
        every { usbManager.deviceList } returns hashMapOf()

        // No ground station WiFi
        val wifiInfo = mockk<WifiInfo>()
        every { wifiInfo.ssid } returns "\"HomeNetwork\""
        @Suppress("DEPRECATION")
        every { wifiManager.connectionInfo } returns wifiInfo

        // Internet available
        val network = mockk<Network>()
        val caps = mockk<NetworkCapabilities>()
        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns caps
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true

        val mode = detector.detect()
        assertTrue("Expected CloudRelay but got $mode", mode is VideoMode.CloudRelay)
    }

    @Test
    fun `no connection available returns NoConnection`() {
        // No USB
        every { usbManager.deviceList } returns hashMapOf()

        // No GS WiFi
        val wifiInfo = mockk<WifiInfo>()
        every { wifiInfo.ssid } returns "\"HomeNetwork\""
        @Suppress("DEPRECATION")
        every { wifiManager.connectionInfo } returns wifiInfo

        // No internet
        every { connectivityManager.activeNetwork } returns null

        val mode = detector.detect()
        assertTrue("Expected NoConnection but got $mode", mode is VideoMode.NoConnection)
    }

    @Test
    fun `USB takes priority over WiFi and internet`() {
        // USB adapter present
        val device = mockk<UsbDevice>()
        every { device.vendorId } returns 0x0BDA
        every { device.productId } returns 0x8812
        every { device.deviceId } returns 99
        every { usbManager.deviceList } returns hashMapOf("dev1" to device)

        // Also on ground station WiFi
        val wifiInfo = mockk<WifiInfo>()
        every { wifiInfo.ssid } returns "\"ADOS-GS-XYZ\""
        @Suppress("DEPRECATION")
        every { wifiManager.connectionInfo } returns wifiInfo

        // Also has internet
        val network = mockk<Network>()
        val caps = mockk<NetworkCapabilities>()
        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns caps
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true

        val mode = detector.detect()
        assertTrue("USB should have priority, got $mode", mode is VideoMode.DirectUsb)
    }

    @Test
    fun `WiFi takes priority over internet when no USB`() {
        // No USB
        every { usbManager.deviceList } returns hashMapOf()

        // Ground station WiFi
        val wifiInfo = mockk<WifiInfo>()
        every { wifiInfo.ssid } returns "\"ADOS-GS-001\""
        @Suppress("DEPRECATION")
        every { wifiManager.connectionInfo } returns wifiInfo

        // Also has internet
        val network = mockk<Network>()
        val caps = mockk<NetworkCapabilities>()
        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns caps
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true

        val mode = detector.detect()
        assertTrue("WiFi should have priority over internet, got $mode", mode is VideoMode.GroundStation)
    }
}
