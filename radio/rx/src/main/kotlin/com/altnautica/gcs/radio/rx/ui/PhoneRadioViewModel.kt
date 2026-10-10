package com.altnautica.gcs.radio.rx.ui

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.node.LanNodeSource
import com.altnautica.gcs.core.data.vault.VaultUnreadableException
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.radio.rx.invite.EnrollmentState
import com.altnautica.gcs.radio.rx.invite.InviteEnrollment
import com.altnautica.gcs.radio.rx.invite.InviteStore
import com.altnautica.gcs.radio.rx.invite.StoredInvite
import com.altnautica.gcs.radio.rx.session.DroneLinkStatus
import com.altnautica.gcs.radio.rx.session.PhoneRadioSession
import com.altnautica.gcs.radio.rx.session.PhoneRadioState
import com.altnautica.gcs.radio.rx.session.PhoneRadioStats
import com.altnautica.gcs.radio.rx.usb.DongleStatus
import com.altnautica.gcs.radio.rx.usb.UsbDongleMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The USB radio as the screen shows it. */
sealed interface DongleUi {
    data object None : DongleUi
    data class Unsupported(val productId: Int) : DongleUi
    data class NeedsPermission(val chip: String) : DongleUi
    data class Ready(val chip: String) : DongleUi
}

data class GroundOption(val id: DeviceId, val name: String, val live: Boolean)

/** The credential vault refused the radio keys just now; the screen offers a retry. */
enum class KeysError {
    /** The stored keys could not be read. Retry reads the vault again. */
    Unreadable,

    /** Forgetting the keys failed; they are still stored. Retry forgets again. */
    NotForgotten,
}

data class PhoneRadioUiState(
    val loading: Boolean = true,
    val dongle: DongleUi = DongleUi.None,
    val invite: StoredInvite? = null,
    val enrollment: EnrollmentState? = null,
    val keysError: KeysError? = null,
    val grounds: List<GroundOption> = emptyList(),
    val radio: PhoneRadioState = PhoneRadioState.Stopped,
    val stats: PhoneRadioStats? = null,
    val links: List<DroneLinkStatus> = emptyList(),
) {
    val canStart: Boolean get() = invite != null && dongle is DongleUi.Ready
}

@HiltViewModel
class PhoneRadioViewModel @Inject constructor(
    private val dongles: UsbDongleMonitor,
    private val store: InviteStore,
    private val enrollment: InviteEnrollment,
    private val session: PhoneRadioSession,
    lan: LanNodeSource,
) : ViewModel() {

    private val enrollmentState = MutableStateFlow<EnrollmentState?>(null)
    private val loaded = MutableStateFlow(false)
    private val keysError = MutableStateFlow<KeysError?>(null)
    private var enrollJob: Job? = null

    private val grounds = lan.nodes.map { nodes ->
        nodes.filter { it.profile == NodeProfile.GroundStation }
            .map { GroundOption(it.id, it.name, it.liveness == Liveness.Live) }
    }

    private val radioPart = combine(session.state, session.stats, session.links) { radio, stats, links -> Triple(radio, stats, links) }

    val state: StateFlow<PhoneRadioUiState> = combine(
        dongles.status.map(::toUi),
        store.invite,
        combine(enrollmentState, loaded, keysError) { e, l, k -> Triple(e, l, k) },
        grounds,
        radioPart,
    ) { dongle, invite, (enroll, isLoaded, error), groundList, (radio, stats, links) ->
        PhoneRadioUiState(
            loading = !isLoaded,
            dongle = dongle,
            invite = invite,
            enrollment = enroll,
            keysError = error,
            grounds = groundList,
            radio = radio,
            stats = stats,
            links = links,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), PhoneRadioUiState())

    init {
        viewModelScope.launch { loadKeys { store.load() } }
    }

    fun requestPermission() {
        val status = dongles.current()
        if (status is DongleStatus.NeedsPermission) dongles.requestPermission(status.device)
    }

    fun enroll(ground: GroundOption) {
        enrollJob?.cancel()
        enrollJob = viewModelScope.launch {
            enrollment.run(ground.id, ground.name, deviceLabel()).collect { enrollmentState.value = it }
        }
    }

    fun cancelEnrollment() {
        enrollJob?.cancel()
        enrollJob = null
        enrollmentState.value = null
    }

    fun forget() {
        viewModelScope.launch {
            session.stop()
            try {
                store.forget()
            } catch (_: VaultUnreadableException) {
                keysError.value = KeysError.NotForgotten
                return@launch
            }
            keysError.value = null
            enrollmentState.value = null
        }
    }

    /** Retries what the vault refused: reading the keys again, or forgetting them again. */
    fun retryKeys() {
        when (keysError.value) {
            KeysError.Unreadable -> viewModelScope.launch { loadKeys { store.reload() } }
            KeysError.NotForgotten -> forget()
            null -> Unit
        }
    }

    /** Stores keys that opened but were not saved, without asking the ground station again. */
    fun saveAgain() {
        val pending = enrollmentState.value as? EnrollmentState.NotSaved ?: return
        viewModelScope.launch { enrollmentState.value = enrollment.save(pending.invite) }
    }

    private suspend fun loadKeys(read: suspend () -> Unit) {
        keysError.value = try {
            read()
            null
        } catch (_: VaultUnreadableException) {
            KeysError.Unreadable
        }
        loaded.value = true
    }

    fun start() = session.start()

    fun stop() {
        viewModelScope.launch { session.stop() }
    }

    private fun toUi(status: DongleStatus): DongleUi = when (status) {
        DongleStatus.None -> DongleUi.None
        is DongleStatus.Unsupported -> DongleUi.Unsupported(status.productId)
        is DongleStatus.NeedsPermission -> DongleUi.NeedsPermission(status.chip)
        is DongleStatus.Ready -> DongleUi.Ready(status.chip)
    }

    private fun deviceLabel(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { !it.isNullOrBlank() }
        .joinToString(" ")
        .ifBlank { "Android phone" }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
