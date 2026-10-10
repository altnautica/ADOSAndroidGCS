package com.altnautica.gcs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.link.vehicle.VehicleRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.replay.ReplayLauncher
import com.altnautica.gcs.replay.ReplayNodeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** App-shell state: whether to ask for the notification permission, and log replays. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val vehicles: VehicleRepository,
    private val dataStore: DataStore<Preferences>,
    private val replays: ReplayLauncher,
) : ViewModel() {

    /**
     * True once the first vehicle link is open and the operator has not yet
     * been asked for the notification permission (the link notification needs it).
     */
    val askNotifications: StateFlow<Boolean> = combine(
        vehicles.openLinks.map { it.isNotEmpty() },
        dataStore.data.map { it[NOTIFICATIONS_ASKED] ?: false },
    ) { open, asked -> open && !asked }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun notificationsAsked() {
        viewModelScope.launch { dataStore.edit { it[NOTIFICATIONS_ASKED] = true } }
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

    private companion object {
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("app.notificationsAsked")
    }
}
