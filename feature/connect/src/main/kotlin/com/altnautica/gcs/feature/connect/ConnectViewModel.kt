package com.altnautica.gcs.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.discovery.DiscoveredAgent
import com.altnautica.gcs.core.agent.discovery.LanDiscovery
import com.altnautica.gcs.core.agent.pairing.PairingFailure
import com.altnautica.gcs.core.agent.pairing.PairingInfo
import com.altnautica.gcs.core.agent.pairing.PairingOutcome
import com.altnautica.gcs.core.agent.pairing.PairingService
import com.altnautica.gcs.core.agent.wifi.GroundStationApStore
import com.altnautica.gcs.core.agent.wifi.GroundStationCredential
import com.altnautica.gcs.core.agent.wifi.WifiConnectionManager
import com.altnautica.gcs.core.agent.wifi.WifiJoinString
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.link.direct.DemoNode
import com.altnautica.gcs.core.link.direct.DirectEndpoint
import com.altnautica.gcs.core.link.direct.DirectLinkSettings
import com.altnautica.gcs.core.link.direct.DirectLinkSource
import com.altnautica.gcs.core.link.direct.UsbFlightController
import com.altnautica.gcs.core.link.direct.UsbFlightControllers
import com.altnautica.gcs.core.link.transport.UsbSerialTransport
import com.altnautica.gcs.core.model.DeviceId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The connect sheet's pages. */
enum class ConnectPage { Home, Code, ManualHost, Usb, Network, WifiJoin }

/** A discovered agent row, with whether this phone already holds its key. */
data class DiscoveredRow(val agent: DiscoveredAgent, val haveKey: Boolean)

/** Why a connect attempt stopped. */
sealed interface ConnectError {
    data object CodeNotFound : ConnectError
    data object AlreadyPaired : ConnectError
    data object Unauthorized : ConnectError
    data object Unreachable : ConnectError
    data object InvalidAnswer : ConnectError
    data class Other(val message: String?) : ConnectError
}

data class ConnectUiState(
    val page: ConnectPage = ConnectPage.Home,
    val discovered: List<DiscoveredRow> = emptyList(),
    val busy: Boolean = false,
    val error: ConnectError? = null,
    /** A node answered as already paired; its API key is needed to add it here. */
    val needsKeyFor: String? = null,
    /** The node a successful step resolved to; the screen navigates there once. */
    val connected: DeviceId? = null,
    // Code page
    val code: String = "",
    val codeError: InputError? = null,
    // Manual host page
    val host: String = "",
    val hostError: InputError? = null,
    val apiKey: String = "",
    val apiKeyError: InputError? = null,
    val probed: PairingInfo? = null,
    // Network page
    val udp: Boolean = true,
    val udpPort: String = DirectEndpoint.DEFAULT_UDP_PORT.toString(),
    val tcpHost: String = "",
    val tcpPort: String = DirectEndpoint.DEFAULT_TCP_PORT.toString(),
    val portError: InputError? = null,
    val tcpHostError: InputError? = null,
    // USB page
    val usbDevices: List<UsbFlightController> = emptyList(),
    val baudRate: Int = UsbSerialTransport.DEFAULT_BAUD,
    // Wi-Fi join page
    val joinText: String = "",
    val joinError: InputError? = null,
    val wifiJoined: Boolean = false,
    // Demo
    val demoEnabled: Boolean = false,
)

/**
 * Every way to add a node: LAN discovery, pairing code, manual host (with an
 * API key for a node paired elsewhere), USB flight controller, UDP/TCP
 * endpoint, ground-station Wi-Fi join, and the demo vehicle.
 */
@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val discovery: LanDiscovery,
    private val pairing: PairingService,
    private val vault: CredentialVault,
    private val directSettings: DirectLinkSettings,
    private val directSource: DirectLinkSource,
    private val usb: UsbFlightControllers,
    private val wifi: WifiConnectionManager,
    private val apStore: GroundStationApStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectUiState())

    val state: StateFlow<ConnectUiState> = combine(
        _state,
        discovery.browse().onStart { emit(emptyList()) },
        vault.nodes,
        combine(usb.devices, directSettings.usbBaudRate, directSettings.demoEnabled, wifi.connected) { d, b, demo, w -> Extra(d, b, demo, w) },
    ) { s, found, stored, extra ->
        val keys = stored.map { it.deviceId }.toSet()
        s.copy(
            discovered = found.map { DiscoveredRow(it, it.deviceId != null && it.deviceId in keys) },
            usbDevices = extra.usb,
            baudRate = extra.baud,
            demoEnabled = extra.demo,
            wifiJoined = extra.wifi,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_GRACE_MS), ConnectUiState())

    private data class Extra(val usb: List<UsbFlightController>, val baud: Int, val demo: Boolean, val wifi: Boolean)

    /** Applies a deep link (`adosgcs://pair?code=&host=`): a code starts the code flow, a host the manual one. */
    fun applyDeepLink(code: String?, host: String?) {
        when {
            !code.isNullOrBlank() -> {
                _state.update { it.copy(page = ConnectPage.Code, code = code, host = host.orEmpty()) }
                submitCode()
            }
            !host.isNullOrBlank() -> _state.update { it.copy(page = ConnectPage.ManualHost, host = host) }
        }
    }

    fun open(page: ConnectPage) = _state.update { it.copy(page = page, error = null, needsKeyFor = null) }

    fun back() = _state.update { it.copy(page = ConnectPage.Home, error = null, needsKeyFor = null, probed = null) }

    fun consumeConnected() = _state.update { it.copy(connected = null) }

    // ---- discovered ----------------------------------------------------------

    /** Pairs a discovered node, or asks for its key when it is paired elsewhere. */
    fun pairDiscovered(row: DiscoveredRow) {
        val agent = row.agent
        val hostPort = hostPort(agent)
        if (row.haveKey) {
            _state.update { it.copy(connected = agent.deviceId?.let(::DeviceId)) }
            return
        }
        if (agent.paired == true) {
            _state.update { it.copy(page = ConnectPage.ManualHost, host = hostPort, needsKeyFor = agent.name ?: agent.deviceId) }
            return
        }
        claim(hostPort)
    }

    // ---- code ----------------------------------------------------------------

    fun setCode(value: String) = _state.update { it.copy(code = value.uppercase(), codeError = null, error = null) }

    fun submitCode() {
        val err = EndpointValidation.code(_state.value.code)
        if (err != null) return _state.update { it.copy(codeError = err) }
        val outcome = pairing.resolveCode(_state.value.code)
        when (outcome) {
            is PairingOutcome.Success -> claim(hostPort(outcome.value))
            is PairingOutcome.Failure -> {
                // The code is not advertised on this network: a host from the deep link still lets the claim go ahead.
                val host = _state.value.host
                if (outcome.failure == PairingFailure.CodeNotFound && EndpointValidation.host(host) == null) {
                    claim(host)
                } else {
                    _state.update { it.copy(error = outcome.failure.toError()) }
                }
            }
        }
    }

    // ---- manual host ---------------------------------------------------------

    fun setHost(value: String) = _state.update { it.copy(host = value, hostError = null, error = null, probed = null) }

    fun setApiKey(value: String) = _state.update { it.copy(apiKey = value, apiKeyError = null, error = null) }

    /** Probes the host; an unpaired node is claimed, a paired one needs its API key. */
    fun submitHost() {
        val s = _state.value
        val hostErr = EndpointValidation.host(s.host)
        val keyErr = EndpointValidation.apiKey(s.apiKey)
        if (hostErr != null || keyErr != null) return _state.update { it.copy(hostError = hostErr, apiKeyError = keyErr) }
        val host = s.host.trim()
        if (s.apiKey.isNotBlank()) return addWithKey(host, s.apiKey.trim())
        launchBusy {
            when (val probe = pairing.probe(host)) {
                is PairingOutcome.Failure -> fail(probe.failure)
                is PairingOutcome.Success -> {
                    val info = probe.value
                    val known = info.deviceId != null && vault.node(DeviceId(info.deviceId!!)) != null
                    when {
                        known -> _state.update { it.copy(busy = false, connected = DeviceId(info.deviceId!!)) }
                        info.paired -> _state.update { it.copy(busy = false, probed = info, needsKeyFor = info.name ?: info.deviceId) }
                        else -> doClaim(host)
                    }
                }
            }
        }
    }

    // ---- USB -----------------------------------------------------------------

    fun refreshUsb() = usb.refresh()

    fun requestUsbPermission(device: UsbFlightController) = directSource.requestUsbPermission(device)

    fun setBaudRate(baud: Int) {
        viewModelScope.launch { directSettings.setUsbBaudRate(baud) }
    }

    fun openUsb(device: UsbFlightController) {
        if (!device.hasPermission) return requestUsbPermission(device)
        _state.update { it.copy(connected = device.nodeId) }
    }

    // ---- UDP / TCP -----------------------------------------------------------

    fun setUdp(udp: Boolean) = _state.update { it.copy(udp = udp, portError = null, tcpHostError = null) }

    fun setUdpPort(v: String) = _state.update { it.copy(udpPort = v.filter(Char::isDigit), portError = null) }

    fun setTcpHost(v: String) = _state.update { it.copy(tcpHost = v, tcpHostError = null) }

    fun setTcpPort(v: String) = _state.update { it.copy(tcpPort = v.filter(Char::isDigit), portError = null) }

    fun submitNetwork() {
        val s = _state.value
        val endpoint: DirectEndpoint = if (s.udp) {
            EndpointValidation.port(s.udpPort)?.let { return _state.update { st -> st.copy(portError = it) } }
            DirectEndpoint.Udp(s.udpPort.trim().toInt())
        } else {
            val hostErr = EndpointValidation.host(s.tcpHost, allowPort = false)
            val portErr = EndpointValidation.port(s.tcpPort)
            if (hostErr != null || portErr != null) return _state.update { it.copy(tcpHostError = hostErr, portError = portErr) }
            DirectEndpoint.Tcp(s.tcpHost.trim().removePrefix("[").removeSuffix("]"), s.tcpPort.trim().toInt())
        }
        viewModelScope.launch {
            directSettings.addEndpoint(endpoint)
            _state.update { it.copy(connected = endpoint.nodeId) }
        }
    }

    // ---- Wi-Fi join ----------------------------------------------------------

    fun setJoinText(v: String) = _state.update { it.copy(joinText = v, joinError = null) }

    /**
     * Joins an ADOS ground-station access point from its join string
     * (`WIFI:T:WPA;S:…;P:…;;`, the code its panel shows) or a typed passphrase.
     * Call only after the nearby-Wi-Fi permission is granted.
     */
    fun joinGroundStationWifi() {
        val raw = _state.value.joinText.trim()
        if (raw.isEmpty()) return _state.update { it.copy(joinError = InputError.Required) }
        val credential = WifiJoinString.parse(raw) ?: GroundStationCredential(passphrase = raw).takeIf { raw.length >= MIN_WPA }
            ?: return _state.update { it.copy(joinError = InputError.KeyTooShort) }
        viewModelScope.launch {
            apStore.store(credential)
            if (!wifi.requestGroundStationNetwork(credential.passphrase, credential.ssidSuffix)) {
                _state.update { it.copy(joinError = InputError.KeyTooShort) }
            }
        }
    }

    // ---- demo ----------------------------------------------------------------

    fun enableDemo() {
        viewModelScope.launch {
            directSettings.setDemoEnabled(true)
            _state.update { it.copy(connected = DemoNode.ID) }
        }
    }

    // ---- plumbing ------------------------------------------------------------

    private fun claim(host: String) = launchBusy { doClaim(host) }

    private suspend fun doClaim(host: String) {
        when (val r = pairing.claim(host)) {
            is PairingOutcome.Success -> _state.update { it.copy(busy = false, connected = r.value.id) }
            is PairingOutcome.Failure -> if (r.failure == PairingFailure.AlreadyPaired) {
                _state.update { it.copy(busy = false, page = ConnectPage.ManualHost, host = host, needsKeyFor = host) }
            } else {
                fail(r.failure)
            }
        }
    }

    private fun addWithKey(host: String, key: String) = launchBusy {
        when (val r = pairing.addWithKey(host, key)) {
            is PairingOutcome.Success -> _state.update { it.copy(busy = false, connected = r.value.id, apiKey = "") }
            is PairingOutcome.Failure -> fail(r.failure)
        }
    }

    private fun launchBusy(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { block() }
    }

    private fun fail(f: PairingFailure) = _state.update { it.copy(busy = false, error = f.toError()) }

    private fun hostPort(agent: DiscoveredAgent): String {
        val h = if (agent.host.contains(':')) "[${agent.host}]" else agent.host
        return "$h:${agent.port}"
    }

    companion object {
        private const val STOP_GRACE_MS = 5_000L
        private const val MIN_WPA = 8

        fun PairingFailure.toError(): ConnectError = when (this) {
            PairingFailure.AlreadyPaired -> ConnectError.AlreadyPaired
            PairingFailure.CodeNotFound -> ConnectError.CodeNotFound
            PairingFailure.InvalidCode, PairingFailure.InvalidHost, PairingFailure.IncompleteAnswer -> ConnectError.InvalidAnswer
            is PairingFailure.Agent -> when (val e = error) {
                com.altnautica.gcs.core.agent.http.AgentError.Unauthorized,
                is com.altnautica.gcs.core.agent.http.AgentError.PinRequired,
                -> ConnectError.Unauthorized
                is com.altnautica.gcs.core.agent.http.AgentError.Network -> ConnectError.Unreachable
                is com.altnautica.gcs.core.agent.http.AgentError.Server -> ConnectError.Other(e.message)
                else -> ConnectError.Other(null)
            }
        }
    }
}
