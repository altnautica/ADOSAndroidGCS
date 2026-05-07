package com.altnautica.gcs

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.altnautica.gcs.data.discovery.NsdAgentDiscovery
import com.altnautica.gcs.data.serial.UsbSerialManager
import com.altnautica.gcs.data.settings.BaseUrlProvider
import com.altnautica.gcs.data.video.ModeDetector
import com.altnautica.gcs.data.video.VideoMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class ModeDetectorTest {

    private lateinit var context: Context
    private lateinit var usbManager: UsbManager
    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var usbSerialManager: UsbSerialManager
    private lateinit var nsdAgentDiscovery: NsdAgentDiscovery
    private lateinit var baseUrlProvider: BaseUrlProvider
    private lateinit var detector: ModeDetector

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        usbManager = mockk(relaxed = true)
        wifiManager = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)
        usbSerialManager = mockk(relaxed = true)
        nsdAgentDiscovery = mockk(relaxed = true)
        baseUrlProvider = mockk(relaxed = true)
        coEvery { baseUrlProvider.setBaseUrl(any()) } returns true

        every { context.getSystemService(Context.USB_SERVICE) } returns usbManager
        every { context.getSystemService(Context.WIFI_SERVICE) } returns wifiManager
        every { context.applicationContext.getSystemService(Context.WIFI_SERVICE) } returns wifiManager
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        // Default: no networks at all unless a test wires them up. This
        // keeps the USB-tether check a no-op for legacy tests.
        every { connectivityManager.allNetworks } returns emptyArray()
        // Default: NSD has not yet resolved anything, so detect() falls
        // back to the hardcoded AP IP.
        every { nsdAgentDiscovery.lastResolved } returns MutableStateFlow(null)

        detector = ModeDetector(context, usbSerialManager, nsdAgentDiscovery, baseUrlProvider)
    }

    private fun mockEthernetNetwork(addressString: String): Network {
        val network = mockk<Network>()
        val caps = mockk<NetworkCapabilities>()
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns true
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_USB) } returns false
        every { caps.hasTransport(any()) } returns false
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns true
        every { connectivityManager.getNetworkCapabilities(network) } returns caps

        val link = mockk<LinkProperties>()
        val inet = InetAddress.getByName(addressString)
        val la = mockk<LinkAddress>()
        every { la.address } returns inet
        every { link.linkAddresses } returns listOf(la)
        every { connectivityManager.getLinkProperties(network) } returns link
        return network
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

    @Test
    fun `discovered NSD endpoint overrides hardcoded fallback`() {
        every { usbManager.deviceList } returns hashMapOf()

        val wifiInfo = mockk<WifiInfo>()
        every { wifiInfo.ssid } returns "\"ADOS-GS-FE12\""
        @Suppress("DEPRECATION")
        every { wifiManager.connectionInfo } returns wifiInfo

        val resolved = NsdAgentDiscovery.AgentEndpoint(
            host = "192.168.4.42",
            port = 8081,
            profile = "ground_station",
            version = "1.2.3",
            deviceId = "abcd",
            path = "/api/v1/ground-station",
        )
        every { nsdAgentDiscovery.lastResolved } returns MutableStateFlow(resolved)

        val mode = detector.detect()
        assertTrue("Expected GroundStation, got $mode", mode is VideoMode.GroundStation)
        val gs = mode as VideoMode.GroundStation
        assertTrue(
            "Expected discovered host in WHEP URL, got ${gs.whepUrl}",
            gs.whepUrl == "http://192.168.4.42:8081/whep",
        )
    }

    @Test
    fun `usb tether subnet matcher accepts in-range addresses`() {
        assertTrue(ModeDetector.isInUsbTetherSubnet("192.168.7.1"))
        assertTrue(ModeDetector.isInUsbTetherSubnet("192.168.7.42"))
        assertTrue(ModeDetector.isInUsbTetherSubnet("192.168.7.255"))
    }

    @Test
    fun `usb tether subnet matcher rejects out-of-range addresses`() {
        assertFalse(ModeDetector.isInUsbTetherSubnet(null))
        assertFalse(ModeDetector.isInUsbTetherSubnet(""))
        assertFalse(ModeDetector.isInUsbTetherSubnet("192.168.4.1"))
        assertFalse(ModeDetector.isInUsbTetherSubnet("10.0.0.1"))
        assertFalse(ModeDetector.isInUsbTetherSubnet("192.168.71.1"))
    }

    @Test
    fun `usb tether returns GroundStation with tether whep url`() {
        every { usbManager.deviceList } returns hashMapOf()
        val tether = mockEthernetNetwork("192.168.7.42")
        every { connectivityManager.allNetworks } returns arrayOf(tether)

        val mode = detector.detect()
        assertTrue("Expected GroundStation, got $mode", mode is VideoMode.GroundStation)
        val gs = mode as VideoMode.GroundStation
        assertEquals("http://192.168.7.1:8080/whep", gs.whepUrl)
    }

    @Test
    fun `usb tether takes priority over WiFi AP when both present`() {
        // No WFB-ng USB adapter
        every { usbManager.deviceList } returns hashMapOf()

        // USB-C tether brings up an ethernet transport in the agent's
        // gadget subnet.
        val tether = mockEthernetNetwork("192.168.7.42")
        every { connectivityManager.allNetworks } returns arrayOf(tether)

        // Phone is also associated with a ground-station SSID.
        val wifiInfo = mockk<WifiInfo>()
        every { wifiInfo.ssid } returns "\"ADOS-GS-001\""
        @Suppress("DEPRECATION")
        every { wifiManager.connectionInfo } returns wifiInfo

        val mode = detector.detect()
        assertTrue("Expected GroundStation via tether, got $mode", mode is VideoMode.GroundStation)
        val gs = mode as VideoMode.GroundStation
        assertEquals("http://192.168.7.1:8080/whep", gs.whepUrl)
    }

    @Test
    fun `non-tether ethernet address is ignored`() {
        every { usbManager.deviceList } returns hashMapOf()
        // An ethernet network on a different subnet (e.g. office LAN)
        // must not be mistaken for the agent gadget interface.
        val notTether = mockEthernetNetwork("10.0.0.5")
        every { connectivityManager.allNetworks } returns arrayOf(notTether)

        val wifiInfo = mockk<WifiInfo>()
        every { wifiInfo.ssid } returns "\"ADOS-GS-001\""
        @Suppress("DEPRECATION")
        every { wifiManager.connectionInfo } returns wifiInfo

        val mode = detector.detect()
        // WiFi AP should still win because the ethernet subnet does not
        // match the agent gadget range.
        assertTrue("Expected WiFi-AP GroundStation, got $mode", mode is VideoMode.GroundStation)
        val gs = mode as VideoMode.GroundStation
        assertEquals("http://192.168.4.1:8080/whep", gs.whepUrl)
    }

    @Test
    fun `falls back to hardcoded AP when NSD has not resolved yet`() {
        every { usbManager.deviceList } returns hashMapOf()

        val wifiInfo = mockk<WifiInfo>()
        every { wifiInfo.ssid } returns "\"ADOS-GS-FE12\""
        @Suppress("DEPRECATION")
        every { wifiManager.connectionInfo } returns wifiInfo

        every { nsdAgentDiscovery.lastResolved } returns MutableStateFlow(null)

        val mode = detector.detect()
        assertTrue("Expected GroundStation, got $mode", mode is VideoMode.GroundStation)
        val gs = mode as VideoMode.GroundStation
        assertTrue(
            "Expected hardcoded fallback in WHEP URL, got ${gs.whepUrl}",
            gs.whepUrl == "http://192.168.4.1:8080/whep",
        )
    }
}
