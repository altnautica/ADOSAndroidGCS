package com.altnautica.gcs.feature.node

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.pairing.PairingFailure
import com.altnautica.gcs.core.agent.pairing.PairingOutcome
import com.altnautica.gcs.core.agent.pairing.PairingService
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.feature.fleet.data.NodeRepository
import com.altnautica.gcs.feature.fleet.data.NodeVitals
import com.altnautica.gcs.feature.fleet.data.NodeVitalsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.feature.node.common.NodeApis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface NodeDetailState {
    data object Loading : NodeDetailState

    /** The node left every source (forgotten, unplugged, or never known). */
    data object Missing : NodeDetailState
    data class Ready(val node: NodeSummary, val tabs: List<NodeTab>, val vitals: NodeVitals) : NodeDetailState
}

sealed interface ForgetState {
    data object Idle : ForgetState
    data object Running : ForgetState
    data object Done : ForgetState
    data class Failed(val message: String) : ForgetState

    /** The phone's credential store could not be read, so the node key is still stored; [NodeDetailViewModel.retryForget] repeats the step. */
    data object CredentialStoreUnavailable : ForgetState
}

@HiltViewModel(assistedFactory = NodeDetailViewModel.Factory::class)
class NodeDetailViewModel @AssistedInject constructor(
    @Assisted nodeId: String,
    nodes: NodeRepository,
    vitals: NodeVitalsRepository,
    private val pairing: PairingService,
    private val apis: NodeApis,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(nodeId: String): NodeDetailViewModel
    }

    private val id = DeviceId(nodeId)

    /**
     * GNSS and LoRa tabs of a ground station follow what its agent reports as
     * fitted (`present`). Re-read every [FEATURE_PROBE_MS]; a failed read keeps
     * the last answer so a poll blip does not flicker the tab row.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val station: Flow<StationFeatures> = nodes.node(id)
        .map { n -> n != null && n.profile == NodeProfile.GroundStation && NodeTabs.hasAgentLink(n) }
        .distinctUntilChanged()
        .flatMapLatest { probe ->
            if (!probe) {
                flowOf(StationFeatures.NONE)
            } else {
                flow {
                    var last = StationFeatures.NONE
                    while (true) {
                        last = probeStation(last)
                        emit(last)
                        delay(FEATURE_PROBE_MS)
                    }
                }
            }
        }
        .onStart { emit(StationFeatures.NONE) }
        .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<NodeDetailState> = combine(
        nodes.nodes,
        nodes.node(id).map { it?.reaches }.distinctUntilChanged().flatMapLatest { reaches ->
            val node = nodes.nodes.value?.firstOrNull { it.id == id }
            if (reaches == null || node == null) flowOf(NodeVitals.UNKNOWN) else vitals.vitals(node).onStart { emit(NodeVitals.UNKNOWN) }
        },
        station,
    ) { list, v, features ->
        when {
            list == null -> NodeDetailState.Loading
            else -> list.firstOrNull { it.id == id }?.let { NodeDetailState.Ready(it, NodeTabs.tabsFor(it, features), v) } ?: NodeDetailState.Missing
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NodeDetailState.Loading)

    private suspend fun probeStation(last: StationFeatures): StationFeatures {
        val api = apis.sensors(id) ?: return StationFeatures.NONE
        val gnss = api.gnss().getOrNull()?.present ?: last.gnss
        val lora = api.lora().getOrNull()?.present ?: last.lora
        return StationFeatures(gnss, lora)
    }

    private val _forget = MutableStateFlow<ForgetState>(ForgetState.Idle)
    val forget: StateFlow<ForgetState> = _forget.asStateFlow()

    /** The step that last ended on [ForgetState.CredentialStoreUnavailable], for [retryForget]. */
    private var lastStep: (() -> Unit)? = null

    /** Releases the node on the agent, then drops its key here. */
    fun unpair() {
        lastStep = ::unpair
        _forget.value = ForgetState.Running
        viewModelScope.launch { _forget.value = pairing.unpair(id).toForgetState() }
    }

    /** Drops the key without contacting the node (it was unpaired elsewhere or is gone). */
    fun forgetLocally() {
        lastStep = ::forgetLocally
        _forget.value = ForgetState.Running
        viewModelScope.launch { _forget.value = pairing.forget(id).toForgetState() }
    }

    /** Repeats the unpair or forget that the credential store refused. */
    fun retryForget() {
        lastStep?.invoke()
    }

    fun clearForget() {
        val s = _forget.value
        if (s is ForgetState.Failed || s == ForgetState.CredentialStoreUnavailable) _forget.value = ForgetState.Idle
    }

    private fun PairingOutcome<Unit>.toForgetState(): ForgetState = when (this) {
        is PairingOutcome.Success -> ForgetState.Done
        is PairingOutcome.Failure ->
            if (failure == PairingFailure.VaultUnavailable) ForgetState.CredentialStoreUnavailable else ForgetState.Failed(failure.toString())
    }

    private companion object {
        const val FEATURE_PROBE_MS = 30_000L
    }
}
