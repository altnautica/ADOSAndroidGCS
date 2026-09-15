package com.altnautica.gcs.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.R
import com.altnautica.gcs.data.pairing.AlreadyPairedError
import com.altnautica.gcs.data.pairing.PairingInfo
import com.altnautica.gcs.data.pairing.PairingRepository
import com.altnautica.gcs.data.settings.BaseUrlProvider
import com.altnautica.gcs.data.wifi.GroundStationApStore
import com.altnautica.gcs.data.wifi.GroundStationCredential
import com.altnautica.gcs.data.wifi.WifiConnectionManager
import com.altnautica.gcs.data.wifi.WifiJoinString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The node's pairing posture, the claim action, and the ground-station AP
 * credential prompt.
 *
 * Kept out of [SettingsViewModel] because it owns a live network probe and two
 * credentials, neither of which the display preferences there have anything to
 * do with.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
    private val apStore: GroundStationApStore,
    private val wifiConnectionManager: WifiConnectionManager,
    private val baseUrlProvider: BaseUrlProvider,
) : ViewModel() {

    val info: StateFlow<PairingInfo?> = pairingRepository.info
    val reachable: StateFlow<Boolean> = pairingRepository.reachable

    /** True when this device holds a key for the node. */
    val hasKey: StateFlow<Boolean> = pairingRepository.apiKey
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The reach the agent reported at claim time, offered as an address. */
    val pairedMdnsHost: StateFlow<String?> = pairingRepository.pairedMdnsHost
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val apPassphraseStored: StateFlow<Boolean> = apStore.passphrase
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Transient result message as a string resource id. */
    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    init {
        probe()
    }

    /** Probe the configured node for identity and pairing posture. */
    fun probe() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            pairingRepository.refresh()
                .onFailure { _message.value = R.string.pairing_unreachable }
            _busy.value = false
        }
    }

    fun pair() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            pairingRepository.claim()
                .onSuccess { _message.value = R.string.pairing_claimed }
                .onFailure { err ->
                    _message.value = when (err) {
                        is AlreadyPairedError -> R.string.pairing_already_paired
                        else -> R.string.pairing_failed
                    }
                }
            _busy.value = false
        }
    }

    fun unpair() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            pairingRepository.unpair()
                .onSuccess { _message.value = R.string.pairing_unpaired_ok }
                .onFailure { _message.value = R.string.pairing_failed }
            _busy.value = false
        }
    }

    fun forgetLocalKey() {
        viewModelScope.launch {
            pairingRepository.forgetLocalKey()
            _message.value = R.string.pairing_forgotten
        }
    }

    /** Adopt the reach the node reported as the agent base URL. */
    fun useReportedReach() {
        val host = pairedMdnsHost.value?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            baseUrlProvider.setBaseUrl(
                "http://$host:${BaseUrlProvider.AGENT_CONTROL_PORT}/",
            )
            pairingRepository.refresh()
        }
    }

    /**
     * Store an operator-supplied AP credential, accepting either a bare
     * passphrase or the WPA join string the node's on-box panel renders.
     */
    fun saveApCredential(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            _message.value = R.string.station_ap_join_needs_passphrase
            return
        }
        val credential = if (trimmed.startsWith("WIFI:", ignoreCase = true)) {
            WifiJoinString.parse(trimmed) ?: run {
                _message.value = R.string.station_ap_invalid_qr
                return
            }
        } else {
            GroundStationCredential(passphrase = trimmed)
        }
        viewModelScope.launch {
            apStore.store(credential)
            _message.value = R.string.station_ap_saved
        }
    }

    fun forgetApCredential() {
        viewModelScope.launch {
            apStore.clear()
            wifiConnectionManager.releaseNetwork()
        }
    }

    /**
     * Join the ground-station AP with the stored credential.
     *
     * Refuses rather than guessing when nothing is stored: the passphrase is
     * generated per unit, so there is no value to attempt.
     */
    fun joinGroundStationAp() {
        viewModelScope.launch {
            val credential = apStore.current()
            if (credential == null) {
                _message.value = R.string.station_ap_join_needs_passphrase
                return@launch
            }
            val requested = wifiConnectionManager.requestGroundStationNetwork(
                passphrase = credential.passphrase,
                ssidSuffix = credential.ssidSuffix,
            )
            _message.value = if (requested) {
                R.string.station_ap_join_requested
            } else {
                R.string.station_ap_join_needs_passphrase
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    @StringRes
    fun statusLabel(info: PairingInfo?, hasKey: Boolean, reachable: Boolean): Int = when {
        !reachable || info == null -> R.string.pairing_status_unknown
        info.paired && hasKey -> R.string.pairing_status_paired
        info.paired -> R.string.pairing_status_paired_elsewhere
        else -> R.string.pairing_status_unpaired
    }
}
