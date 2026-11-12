package com.altnautica.gcs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import com.altnautica.gcs.core.data.home.SelectedVehicleStore
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.link.vehicle.VehicleRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.feature.configure.wizard.GroundSetupCheck
import com.altnautica.gcs.navigation.HomeKey
import com.altnautica.gcs.replay.ReplayLauncher
import com.altnautica.gcs.replay.ReplayNodeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A connected vehicle whose flight controller [uid] never finished the ground setup wizard. */
data class SetupOffer(val uid: String, val nodeId: DeviceId)

/** App-shell state: the notification permission ask, the aircraft setup offer, log replays, and the post-connect selection. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppViewModel @Inject constructor(
    private val vehicles: VehicleRepository,
    private val dataStore: DataStore<Preferences>,
    private val replays: ReplayLauncher,
    private val selectedVehicle: SelectedVehicleStore,
    private val groundSetup: GroundSetupCheck,
    nodeRepository: NodeRepository,
) : ViewModel() {

    private val homeOnTop = MutableStateFlow(false)

    /** True while any open vehicle's last heartbeat reports armed. */
    private val anyArmed: Flow<Boolean> = vehicles.openVehicles
        .flatMapLatest { open ->
            if (open.isEmpty()) {
                flowOf(false)
            } else {
                combine(open.values.map { v -> v.telemetry.heartbeat.map { it?.armed == true } }) { armed -> armed.any { it } }
            }
        }
        .distinctUntilChanged()

    /** Latches once the first vehicle link opens: the ask stays queued after that link closes. */
    private val linkEverOpen: Flow<Boolean> = vehicles.openLinks
        .map { it.isNotEmpty() }
        .runningFold(false) { seen, open -> seen || open }
        .distinctUntilChanged()

    /**
     * True once a vehicle link has opened and the operator has not yet been asked
     * for the notification permission (the link notification needs it), but only
     * while Home is on top and no open vehicle is armed. Never over a cockpit.
     */
    val askNotifications: StateFlow<Boolean> = combine(
        linkEverOpen,
        dataStore.data.map { it[NOTIFICATIONS_ASKED] ?: false },
        homeOnTop,
        anyArmed,
    ) { open, asked, home, armed -> open && !asked && home && !armed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun notificationsAsked() {
        viewModelScope.launch { dataStore.edit { it[NOTIFICATIONS_ASKED] = true } }
    }

    /** The shell reports the route on top of its back stack. */
    fun topRouteChanged(top: NavKey?) {
        homeOnTop.value = top == HomeKey
    }

    private val setupCandidates = MutableStateFlow<List<SetupOffer>>(emptyList())
    private val offeredUids: Flow<Set<String>> = dataStore.data.map { it[SETUP_OFFERED].orEmpty() + it[LEGACY_SETUP_OFFERED].orEmpty() }

    init {
        viewModelScope.launch {
            dataStore.edit { if (LEGACY_SETUP_OFFERED in it) it[SETUP_OFFERED] = it.takeOffered() }
        }
    }

    /**
     * The aircraft setup offer to show, or null: the selected vehicle, while it is reachable
     * (a link open, or the node live: Home opens no link of its own), when its flight
     * controller never finished the ground wizard and was never offered on this device.
     * A vehicle that drops out hides the offer until it comes back. Ground only: null
     * while any open vehicle is armed.
     */
    private val reachable: StateFlow<Set<DeviceId>> = combine(vehicles.openVehicles, nodeRepository.nodes) { open, nodes ->
        open.keys + nodes.orEmpty().filter { it.liveness == Liveness.Live }.map { it.id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val setupOffer: StateFlow<SetupOffer?> = combine(
        setupCandidates,
        offeredUids,
        anyArmed,
        reachable,
        selectedVehicle.selected,
    ) { candidates, offered, armed, reach, selected ->
        if (armed) null else candidates.firstOrNull { it.uid !in offered && it.nodeId in reach && it.nodeId == selected }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val openWizard = Channel<DeviceId>(Channel.BUFFERED)

    /** Nodes whose ground setup wizard the operator chose to open from the offer. */
    val setupNavigation: Flow<DeviceId> = openWizard.receiveAsFlow()

    /** Opens the ground wizard for the current offer (via [setupNavigation]) and marks it offered. */
    fun openSetup() {
        val offer = liveOffer() ?: return
        openWizard.trySend(offer.nodeId)
        markOffered(offer.uid)
    }

    /** Dismisses the current offer for good on this device. */
    fun later() {
        val offer = liveOffer() ?: return
        markOffered(offer.uid)
    }

    /** The current offer while its vehicle is still reachable: a gone vehicle never spends its one offer. */
    private fun liveOffer(): SetupOffer? = setupOffer.value?.takeIf { it.nodeId in reachable.value }

    private fun markOffered(uid: String) {
        setupCandidates.update { list -> list.filterNot { it.uid == uid } }
        viewModelScope.launch { dataStore.edit { it[SETUP_OFFERED] = it.takeOffered() + uid } }
    }

    /** Opens a log replay and hands its node id to [onReady] (to push the cockpit). */
    fun startReplay(tlogPath: String, onReady: (DeviceId) -> Unit) {
        viewModelScope.launch { onReady(replays.open(tlogPath)) }
    }

    /** Leaving a cockpit: a replay closes; a live link closes itself once unobserved. */
    fun cockpitClosed(id: String) {
        if (ReplayNodeSource.isReplay(id)) viewModelScope.launch { replays.close(DeviceId(id)) }
    }

    /** Flying a node again lifts any Disconnect the notification applied. */
    fun reconnect(id: DeviceId) = vehicles.connect(id)

    /**
     * A new connection becomes Home's selected vehicle; [onSelected] then returns to Home.
     * When the vehicle's flight controller has never finished the ground setup wizard,
     * it becomes a [setupOffer] candidate (shown unless already offered here).
     */
    fun connected(id: DeviceId, onSelected: () -> Unit) {
        viewModelScope.launch {
            selectedVehicle.select(id)
            onSelected()
            val uid = groundSetup.uidNeedingSetup(id) ?: return@launch
            setupCandidates.update { list -> if (list.any { it.uid == uid }) list else list + SetupOffer(uid, id) }
        }
    }

    private companion object {
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("app.notificationsAsked")

        /** Flight-controller uids already offered aircraft setup on this device. */
        val SETUP_OFFERED = stringSetPreferencesKey("setup.offered_uids")

        /** The same set under its earlier name; folded into [SETUP_OFFERED] and removed on first use. */
        val LEGACY_SETUP_OFFERED = stringSetPreferencesKey("app.aircraftSetupOffered")

        /** The offered uids, folding in and removing the set stored under the earlier key name. */
        fun MutablePreferences.takeOffered(): Set<String> = this[SETUP_OFFERED].orEmpty() + remove(LEGACY_SETUP_OFFERED).orEmpty()
    }
}
