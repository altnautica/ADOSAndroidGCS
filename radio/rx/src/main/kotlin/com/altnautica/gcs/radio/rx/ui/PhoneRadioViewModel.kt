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
import com.altnautica.gcs.radio.rx.tx.UplinkState
import com.altnautica.gcs.radio.rx.usb.DongleStatus
import com.altnautica.gcs.radio.rx.usb.UsbDongleMonitor
import com.altnautica.gcs.core.link.fleet.BoundFleet
import com.altnautica.gcs.core.link.fleet.FleetGroundStore
import com.altnautica.gcs.core.link.fleet.RelaySecretState
import com.altnautica.gcs.core.link.fleet.SlotReconciler
import com.altnautica.gcs.radio.rx.bind.BindController
import com.altnautica.gcs.radio.rx.bind.BindState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import com.altnautica.gcs.radio.rx.uplink.HandoverState
import com.altnautica.gcs.radio.rx.uplink.UplinkHandover
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
    /** The uplink while the phone holds it; null while receive only. */
    val uplink: UplinkState? = null,
    /** The phone's side of the uplink handover. */
    val handover: HandoverState = HandoverState(),
    /** The box the stored invite came from; null with no ground-role invite, or before the first read ([handoverChecked]). */
    val handoverBox: HandoverBoxUi? = null,
    /** The first read of the stored invite's box finished. */
    val handoverChecked: Boolean = false,
    /** The session runs on a radio that is not the USB dongle (a debug build's bench relay). */
    val alternateRadio: Boolean = false,
    /** The fleet this phone bound over the air, null when it bound none. */
    val fleet: BoundFleetUi? = null,
    val bind: BindState = BindState.Idle,
    /** The operator opened the Bind section ("Bind a drone"), or a bind ran. */
    val bindOpen: Boolean = false,
) {
    val canStart: Boolean get() = (invite != null || fleet != null) && (dongle is DongleUi.Ready || alternateRadio)
}

@HiltViewModel
class PhoneRadioViewModel @Inject constructor(
    private val dongles: UsbDongleMonitor,
    private val store: InviteStore,
    private val enrollment: InviteEnrollment,
    private val session: PhoneRadioSession,
    lan: LanNodeSource,
    private val fleetStore: FleetGroundStore,
    private val binder: BindController,
    reconciler: SlotReconciler,
    private val handover: UplinkHandover,
) : ViewModel() {

    private val enrollmentState = MutableStateFlow<EnrollmentState?>(null)
    private val loaded = MutableStateFlow(false)
    private val keysError = MutableStateFlow<KeysError?>(null)
    private val bindOpen = MutableStateFlow(false)
    private var enrollJob: Job? = null

    private val bindPart = combine(fleetStore.fleet, reconciler.secrets, binder.state, bindOpen) { fleet, secrets, bind, open ->
        BindPart(fleet?.let { toUi(it, secrets) }, bind, open || bind != BindState.Idle)
    }

    private val grounds = lan.nodes.map { nodes ->
        nodes.filter { it.profile == NodeProfile.GroundStation }
            .map { GroundOption(it.id, it.name, it.liveness == Liveness.Live) }
    }

    /** The box's uplink, read every few seconds while the screen is shown; null until the first read finished. */
    private val handoverBox = flow<BoxRead?> {
        emit(null)
        while (true) {
            emit(BoxRead(handover.boxStatus()?.let { (box, status) -> HandoverBoxUi(box.deviceId, box.name, status) }))
            delay(BOX_POLL_MS)
        }
    }

    private val handoverPart = combine(handover.state, handoverBox, ::Pair)

    private val radioPart = combine(session.state, session.stats, session.links, session.uplink, handoverPart) { radio, stats, links, uplink, part ->
        RadioPart(radio, stats, links, uplink, part.first, part.second)
    }

    val state: StateFlow<PhoneRadioUiState> = combine(
        dongles.status.map(::toUi),
        store.invite,
        combine(enrollmentState, loaded, keysError) { e, l, k -> Triple(e, l, k) },
        combine(grounds, bindPart, ::Pair),
        radioPart,
    ) { dongle, invite, (enroll, isLoaded, error), (groundList, bind), radio ->
        PhoneRadioUiState(
            loading = !isLoaded,
            dongle = dongle,
            invite = invite,
            enrollment = enroll,
            keysError = error,
            grounds = groundList,
            radio = radio.state,
            stats = radio.stats,
            links = radio.links,
            uplink = radio.uplink,
            handover = radio.handover,
            handoverBox = radio.handoverBox?.box,
            handoverChecked = radio.handoverBox != null,
            alternateRadio = session.alternateRadioAvailable,
            fleet = bind.fleet,
            bind = bind.state,
            bindOpen = bind.open,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), PhoneRadioUiState())

    init {
        viewModelScope.launch {
            loadKeys {
                store.load()
                fleetStore.load()
            }
        }
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
                fleetStore.forget()
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
            KeysError.Unreadable -> viewModelScope.launch {
                loadKeys {
                    store.reload()
                    fleetStore.load()
                }
            }
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

    /** "Bind a drone": opens the Bind section. */
    fun openBind() {
        bindOpen.value = true
    }

    fun startBind() {
        bindOpen.value = true
        binder.start()
    }

    fun cancelBind() = binder.cancel()

    private fun toUi(fleet: BoundFleet, secrets: Map<String, RelaySecretState>) = BoundFleetUi(
        fleetId = fleet.fleetId,
        channel = fleet.channel,
        keyFingerprint = fleet.keyFingerprint,
        keyProven = fleet.keyProven,
        drones = fleet.slots.map { FleetDroneUi(it.slot, it.deviceId, secrets[it.deviceId] ?: RelaySecretState.Pending) },
    )

    fun start() = session.start()

    fun takeUplink() {
        viewModelScope.launch { handover.take() }
    }

    fun giveBackUplink() {
        viewModelScope.launch { handover.giveBack() }
    }

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
        const val BOX_POLL_MS = 3_000L
    }
}

private data class RadioPart(
    val state: PhoneRadioState,
    val stats: PhoneRadioStats?,
    val links: List<DroneLinkStatus>,
    val uplink: UplinkState?,
    val handover: HandoverState,
    val handoverBox: BoxRead?,
)

/** One read of the stored invite's box. */
private data class BoxRead(val box: HandoverBoxUi?)

private data class BindPart(val fleet: BoundFleetUi?, val state: BindState, val open: Boolean)
