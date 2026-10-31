package com.altnautica.gcs.feature.configure.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.NodeSummary
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The vehicle a Setup or Parameters surface works on, as its header names it. */
sealed interface SurfaceNodeState {
    data object Loading : SurfaceNodeState

    /** [name] falls back to the id, and [fcVariant] to unknown, for a node no source reports. */
    data class Ready(val name: String, val fcVariant: FcVariant) : SurfaceNodeState
}

/** Reads the node's name and flight-controller variant, so the shell needs no node lookup. */
@HiltViewModel(assistedFactory = SetupSurfaceViewModel.Factory::class)
class SetupSurfaceViewModel internal constructor(
    nodeId: DeviceId,
    nodes: Flow<List<NodeSummary>?>,
) : ViewModel() {
    @AssistedInject constructor(@Assisted nodeId: String, repository: NodeRepository) :
        this(DeviceId(nodeId), repository.nodes)

    @AssistedFactory
    interface Factory {
        // A String id: KSP cannot name a factory method that takes an inline value class.
        fun create(nodeId: String): SetupSurfaceViewModel
    }

    val state: StateFlow<SurfaceNodeState> = nodes
        .map { list ->
            if (list == null) {
                SurfaceNodeState.Loading
            } else {
                val node = list.firstOrNull { it.id == nodeId }
                SurfaceNodeState.Ready(node?.name ?: nodeId.value, node?.fcVariant ?: FcVariant.Unknown)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SurfaceNodeState.Loading)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
