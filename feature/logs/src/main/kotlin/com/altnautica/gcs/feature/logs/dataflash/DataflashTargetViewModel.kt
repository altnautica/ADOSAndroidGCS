package com.altnautica.gcs.feature.logs.dataflash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.data.home.SelectedVehicleStore
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The vehicle a flight-controller log download targets: the one selected on Home. */
data class DataflashTarget(val id: DeviceId, val name: String)

@HiltViewModel
class DataflashTargetViewModel internal constructor(
    selected: Flow<DeviceId?>,
    nodes: Flow<List<NodeSummary>?>,
) : ViewModel() {
    @Inject constructor(store: SelectedVehicleStore, repository: NodeRepository) :
        this(store.selected, repository.nodes)

    /** Null when no vehicle is selected, or the stored one is no longer listed. */
    val target: StateFlow<DataflashTarget?> = combine(selected, nodes) { id, list ->
        val node = id?.let { wanted -> list.orEmpty().firstOrNull { it.id == wanted } }
        node?.let { DataflashTarget(it.id, it.name) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
