package com.altnautica.gcs.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.discovery.DiscoveredAgent
import com.altnautica.gcs.core.agent.discovery.LanDiscovery
import com.altnautica.gcs.core.agent.discovery.PairingCode
import com.altnautica.gcs.core.agent.pairing.PairingFailure
import com.altnautica.gcs.core.agent.pairing.PairingInfo
import com.altnautica.gcs.core.agent.pairing.PairingOutcome
import com.altnautica.gcs.core.agent.pairing.PairingService
import com.altnautica.gcs.core.agent.wifi.GroundStationApStore
import com.altnautica.gcs.core.agent.wifi.GroundStationCredential
import com.altnautica.gcs.core.agent.wifi.WifiConnectionManager
import com.altnautica.gcs.core.agent.wifi.WifiJoinState
import com.altnautica.gcs.core.agent.wifi.WifiJoinString
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.demo.DemoSettings
import com.altnautica.gcs.core.link.direct.DirectEndpoint
import com.altnautica.gcs.core.link.direct.DirectLinkSettings
import com.altnautica.gcs.core.link.direct.DirectLinkSource
import com.altnautica.gcs.core.link.direct.UsbFlightController
import com.altnautica.gcs.core.link.direct.UsbFlightControllers
import com.altnautica.gcs.core.link.transport.UsbSerialTransport
import com.altnautica.gcs.core.model.DeviceId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    /** The phone's credential store could not be read; nothing was saved and the same step can be retried. */
    data object CredentialStoreUnavailable : ConnectError
    data class Other(val message: String?) : ConnectError
}

data class ConnectUiState(
    val page: ConnectPage = ConnectPage.Home,
    val discovered: List<DiscoveredRow> = emptyList(),
    /** Discovery has run [ConnectViewModel.DISCOVERY_HELP_MS] without finding any node. */
    val discoverySlow: Boolean = false,
    val busy: Boolean = false,
    /** While [busy]: waiting for discovery to find the node (true) rather than pairing with it. */
    val searching: Boolean = false,
    val error: ConnectError? = null,
    /** A node answered as already paired; its API key is needed to add it here. */
    val needsKeyFor: String? = null,
    /** The node a successful step resolved to; the screen navigates there once. */
    val connected: DeviceId? = null,
    // Code page
    val code: String = "",
    val codeError: InputError? = null,
    /** The code and host came from a pairing link: shown for the operator to check before Pair. */
    val fromLink: Boolean = false,
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
    val udpPortError: InputError? = null,
    val tcpPortError: InputError? = null,
    val tcpHostError: InputError? = null,
    // USB page
    val usbDevices: List<UsbFlightController> = emptyList(),
    val baudRate: Int = UsbSerialTransport.DEFAULT_BAUD,
    // Wi-Fi join page
    val joinText: String = "",
    val joinError: InputError? = null,
    val wifiJoin: WifiJoinState = WifiJoinState.Idle,
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
    private val demo: DemoSettings,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectUiState())

    /** Bumped by [retryDiscovery]; each value restarts the browse and its help timer. */
    private val discoveryAttempt = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val discoveryFlow = discoveryAttempt.flatMapLatest {
        val found = discovery.browse().onStart { emit(emptyList()) }
        val elapsed = flow {
            emit(false)
            delay(DISCOVERY_HELP_MS)
            emit(true)
        }
        combine(found, elapsed) { agents, late -> agents to late }
    }

    val state: StateFlow<ConnectUiState> = combine(
        _state,
        discoveryFlow,
        vault.nodes,
        combine(usb.devices, directSettings.usbBaudRate, demo.enabled, wifi.joinState) { d, b, on, w -> Extra(d, b, on, w) },
    ) { s, (found, late), stored, extra ->
        val keys = stored.map { it.deviceId }.toSet()
        s.copy(
            discovered = found.map { DiscoveredRow(it, it.deviceId != null && it.deviceId in keys) },
            discoverySlow = late && found.isEmpty(),
            usbDevices = extra.usb,
            baudRate = extra.baud,
            demoEnabled = extra.demo,
            wifiJoin = extra.wifi,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_GRACE_MS), ConnectUiState())

    /** Restarts LAN discovery and its help timer. */
    fun retryDiscovery() = discoveryAttempt.update { it + 1 }

    private data class Extra(val usb: List<UsbFlightController>, val baud: Int, val demo: Boolean, val wifi: WifiJoinState)

    /**
     * Applies a deep link (`adosgcs://pair?code=&host=`): fills the Code page (or the manual host page) and
     * waits for the operator's Pair tap; a link never claims on its own. A link opened on a cold start arrives
     * before discovery has resolved anything, so Pair on a linked code without a usable host waits up to
     * [DEEP_LINK_DISCOVERY_MS] for a node to advertise it before reporting it missing.
     */
    fun applyDeepLink(code: String?, host: String?) {
        when {
            !code.isNullOrBlank() -> _state.update {
                it.copy(
                    page = ConnectPage.Code,
                    code = code.uppercase(),
                    host = host.orEmpty(),
                    fromLink = true,
                    codeError = EndpointValidation.code(code),
                    error = null,
                )
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

    /** Editing the code drops the linked host: it belonged to the linked code. */
    fun setCode(value: String) = _state.update {
        it.copy(code = value.uppercase(), codeError = null, error = null, host = if (it.fromLink) "" else it.host, fromLink = false)
    }

    fun submitCode() {
        val s = _state.value
        val err = EndpointValidation.code(s.code)
        if (err != null) return _state.update { it.copy(codeError = err) }
        val wanted = PairingCode.normalize(s.code)
        val waitForDiscovery = s.fromLink && EndpointValidation.host(s.host) != null
        launchBusy {
            if (waitForDiscovery) {
                _state.update { it.copy(searching = true) }
                withTimeoutOrNull(DEEP_LINK_DISCOVERY_MS) {
                    discovery.browse().first { found -> found.any { it.code == wanted } }
                }
                _state.update { it.copy(searching = false) }
            }
            resolveAndClaim()
        }
    }

    private suspend fun resolveAndClaim() {
        when (val outcome = pairing.resolveCode(_state.value.code)) {
            is PairingOutcome.Success -> doClaim(hostPort(outcome.value))
            is PairingOutcome.Failure -> {
                // The code is not advertised on this network: a host from the deep link still lets the claim go ahead.
                val host = _state.value.host
                if (outcome.failure == PairingFailure.CodeNotFound && EndpointValidation.host(host) == null) {
                    doClaim(host)
                } else {
                    fail(outcome.failure)
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
        // A claim whose key the credential store refused: the node now reports itself
        // paired, so the retry saves the key it already handed out instead of probing.
        if (pairing.hasUnsavedClaim(host)) return launchBusy { doClaim(host) }
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

    fun setUdp(udp: Boolean) = _state.update { it.copy(udp = udp, udpPortError = null, tcpPortError = null, tcpHostError = null) }

    fun setUdpPort(v: String) = _state.update { it.copy(udpPort = v.filter(Char::isDigit), udpPortError = null) }

    fun setTcpHost(v: String) = _state.update { it.copy(tcpHost = v, tcpHostError = null) }

    fun setTcpPort(v: String) = _state.update { it.copy(tcpPort = v.filter(Char::isDigit), tcpPortError = null) }

    fun submitNetwork() {
        val s = _state.value
        if (s.busy) return
        val endpoint: DirectEndpoint = if (s.udp) {
            EndpointValidation.port(s.udpPort)?.let { return _state.update { st -> st.copy(udpPortError = it) } }
            DirectEndpoint.Udp(s.udpPort.trim().toInt())
        } else {
            val hostErr = EndpointValidation.host(s.tcpHost, allowPort = false)
            val portErr = EndpointValidation.port(s.tcpPort)
            if (hostErr != null || portErr != null) return _state.update { it.copy(tcpHostError = hostErr, tcpPortError = portErr) }
            DirectEndpoint.Tcp(s.tcpHost.trim().removePrefix("[").removeSuffix("]"), s.tcpPort.trim().toInt())
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            directSettings.addEndpoint(endpoint)
            _state.update { it.copy(busy = false, connected = endpoint.nodeId) }
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
        if (!wifi.requestGroundStationNetwork(credential.passphrase, credential.ssidSuffix)) {
            return _state.update { it.copy(joinError = InputError.Required) }
        }
        // Keep the credential only once the station actually joined.
        viewModelScope.launch {
            val result = wifi.joinState.first { it == WifiJoinState.Joined || it == WifiJoinState.Unavailable }
            if (result == WifiJoinState.Joined) apStore.store(credential)
        }
    }

    // ---- demo ----------------------------------------------------------------

    fun enableDemo() {
        viewModelScope.launch {
            val id = demo.enable()
            _state.update { it.copy(connected = id) }
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
        /** How long a deep-linked code waits for LAN discovery to find its node. */
        const val DEEP_LINK_DISCOVERY_MS = 10_000L
        /** How long discovery runs empty before the screen shows network help. */
        const val DISCOVERY_HELP_MS = 10_000L
        private const val MIN_WPA = 8

        fun PairingFailure.toError(): ConnectError = when (this) {
            PairingFailure.AlreadyPaired -> ConnectError.AlreadyPaired
            PairingFailure.CodeNotFound -> ConnectError.CodeNotFound
            PairingFailure.InvalidCode, PairingFailure.InvalidHost, PairingFailure.IncompleteAnswer -> ConnectError.InvalidAnswer
            PairingFailure.VaultUnavailable -> ConnectError.CredentialStoreUnavailable
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
