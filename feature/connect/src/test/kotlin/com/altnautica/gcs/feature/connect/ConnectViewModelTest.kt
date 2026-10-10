package com.altnautica.gcs.feature.connect

import com.altnautica.gcs.core.agent.discovery.DiscoveredAgent
import com.altnautica.gcs.core.agent.discovery.LanDiscovery
import com.altnautica.gcs.core.agent.pairing.PairingFailure
import com.altnautica.gcs.core.agent.pairing.PairingInfo
import com.altnautica.gcs.core.agent.pairing.PairingOutcome
import com.altnautica.gcs.core.agent.pairing.PairingService
import com.altnautica.gcs.core.agent.wifi.GroundStationApStore
import com.altnautica.gcs.core.agent.wifi.WifiConnectionManager
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.data.vault.NodeCredential
import com.altnautica.gcs.core.link.direct.DirectEndpoint
import com.altnautica.gcs.core.link.direct.DirectLinkSettings
import com.altnautica.gcs.core.link.direct.DirectLinkSource
import com.altnautica.gcs.core.link.direct.UsbFlightControllers
import com.altnautica.gcs.core.model.DeviceId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val pairing = mockk<PairingService>()
    private val vault = mockk<CredentialVault> {
        every { nodes } returns MutableStateFlow(emptyList())
        every { node(any()) } returns null
    }
    private val direct = mockk<DirectLinkSettings>(relaxed = true) {
        every { usbBaudRate } returns flowOf(57_600)
        every { demoEnabled } returns flowOf(false)
    }
    private val agent = DiscoveredAgent(
        serviceName = "ados-drone", deviceId = "drone-1", name = "Drone", version = "1", board = null,
        paired = false, profile = "drone", role = null, code = "ABCD2345", host = "192.168.1.50", port = 8080, lastSeenMs = 0,
    )

    private fun vm() = ConnectViewModel(
        discovery = mockk<LanDiscovery> { every { browse() } returns flowOf(listOf(agent)) },
        pairing = pairing,
        vault = vault,
        directSettings = direct,
        directSource = mockk<DirectLinkSource>(relaxed = true),
        usb = mockk<UsbFlightControllers>(relaxed = true) { every { devices } returns MutableStateFlow(emptyList()) },
        wifi = mockk<WifiConnectionManager>(relaxed = true) { every { connected } returns MutableStateFlow(false) },
        apStore = mockk<GroundStationApStore>(relaxed = true),
    )

    private fun credential(id: String) = NodeCredential(deviceId = id, apiKey = "k".repeat(32))

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun codePairingClaimsTheAdvertisingNode() = runTest(dispatcher) {
        every { pairing.resolveCode("ABCD-2345") } returns PairingOutcome.Success(agent)
        coEvery { pairing.claim("192.168.1.50:8080", null) } returns PairingOutcome.Success(credential("drone-1"))
        val vm = vm()
        backgroundScope.launch { vm.state.collect {} }

        vm.setCode("abcd-2345")
        vm.submitCode()

        assertEquals(DeviceId("drone-1"), vm.state.value.connected)
        assertEquals(1, vm.state.value.discovered.size)
    }

    @Test
    fun invalidCodeIsRejectedLocally() = runTest(dispatcher) {
        val vm = vm()
        backgroundScope.launch { vm.state.collect {} }
        vm.setCode("IO01")
        vm.submitCode()
        assertEquals(InputError.InvalidCode, vm.state.value.codeError)
        coVerify(exactly = 0) { pairing.claim(any(), any()) }
    }

    @Test
    fun unknownCodeReportsNotFound() = runTest(dispatcher) {
        every { pairing.resolveCode(any()) } returns PairingOutcome.Failure(PairingFailure.CodeNotFound)
        val vm = vm()
        backgroundScope.launch { vm.state.collect {} }
        vm.setCode("ABCD2345")
        vm.submitCode()
        assertEquals(ConnectError.CodeNotFound, vm.state.value.error)
    }

    @Test
    fun manualHostClaimsAnUnpairedNode() = runTest(dispatcher) {
        coEvery { pairing.probe("192.168.1.50") } returns PairingOutcome.Success(PairingInfo(deviceId = "drone-1", paired = false))
        coEvery { pairing.claim("192.168.1.50", null) } returns PairingOutcome.Success(credential("drone-1"))
        val vm = vm()
        backgroundScope.launch { vm.state.collect {} }
        vm.setHost("192.168.1.50")
        vm.submitHost()
        assertEquals(DeviceId("drone-1"), vm.state.value.connected)
    }

    @Test
    fun manualHostOfAPairedNodeNeedsItsKeyThenAddsWithKey() = runTest(dispatcher) {
        coEvery { pairing.probe("192.168.1.50") } returns PairingOutcome.Success(PairingInfo(deviceId = "drone-1", name = "Drone", paired = true))
        val key = "0123456789abcdef0123456789abcdef"
        coEvery { pairing.addWithKey("192.168.1.50", key) } returns PairingOutcome.Success(credential("drone-1"))
        val vm = vm()
        backgroundScope.launch { vm.state.collect {} }

        vm.setHost("192.168.1.50")
        vm.submitHost()
        assertEquals("Drone", vm.state.value.needsKeyFor)
        assertNull(vm.state.value.connected)
        coVerify(exactly = 0) { pairing.claim(any(), any()) }

        vm.setApiKey(key)
        vm.submitHost()
        assertEquals(DeviceId("drone-1"), vm.state.value.connected)
    }

    @Test
    fun manualHostValidatesBeforeSending() = runTest(dispatcher) {
        val vm = vm()
        backgroundScope.launch { vm.state.collect {} }
        vm.setHost("http://x")
        vm.setApiKey("short")
        vm.submitHost()
        assertEquals(InputError.InvalidHost, vm.state.value.hostError)
        assertEquals(InputError.KeyTooShort, vm.state.value.apiKeyError)
        coVerify(exactly = 0) { pairing.probe(any()) }
    }

    @Test
    fun udpAndTcpEndpointsValidateAndSave() = runTest(dispatcher) {
        val vm = vm()
        backgroundScope.launch { vm.state.collect {} }

        vm.setUdpPort("70000")
        vm.submitNetwork()
        assertEquals(InputError.InvalidPort, vm.state.value.portError)

        vm.setUdpPort("14550")
        vm.submitNetwork()
        coVerify { direct.addEndpoint(DirectEndpoint.Udp(14550)) }
        assertEquals(DeviceId("udp:14550"), vm.state.value.connected)
        vm.consumeConnected()

        vm.setUdp(false)
        vm.setTcpHost("")
        vm.submitNetwork()
        assertEquals(InputError.Required, vm.state.value.tcpHostError)

        vm.setTcpHost("192.168.1.50")
        vm.setTcpPort("5760")
        vm.submitNetwork()
        coVerify { direct.addEndpoint(DirectEndpoint.Tcp("192.168.1.50", 5760)) }
        assertEquals(DeviceId("tcp:192.168.1.50:5760"), vm.state.value.connected)
    }

    @Test
    fun demoTurnsOnAndOpensTheDemoNode() = runTest(dispatcher) {
        val vm = vm()
        backgroundScope.launch { vm.state.collect {} }
        vm.enableDemo()
        coVerify { direct.setDemoEnabled(true) }
        assertEquals(com.altnautica.gcs.core.link.direct.DemoNode.ID, vm.state.value.connected)
    }

    @Test
    fun deepLinkCodeStartsTheCodeFlow() = runTest(dispatcher) {
        every { pairing.resolveCode(any()) } returns PairingOutcome.Failure(PairingFailure.CodeNotFound)
        coEvery { pairing.claim("192.168.1.50", null) } returns PairingOutcome.Success(credential("drone-1"))
        val vm = vm()
        backgroundScope.launch { vm.state.collect {} }
        // The code is not advertised here, so the deep link's host is claimed directly.
        vm.applyDeepLink("ABCD2345", "192.168.1.50")
        assertEquals(DeviceId("drone-1"), vm.state.value.connected)
    }
}
