package com.altnautica.gcs.core.extensions.parameters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.extensions.data.ApiResult
import com.altnautica.gcs.core.extensions.data.ExtensionRepository
import com.altnautica.gcs.core.extensions.data.RosterCamera
import com.altnautica.gcs.core.extensions.data.WriteOutcome
import com.altnautica.gcs.core.extensions.manifest.scalar
import com.altnautica.gcs.core.model.DeviceId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

/** One rendered group of parameters (declaration order within `ui.order`). */
data class ParameterGroup(val title: String?, val items: List<PluginParameter>)

data class ParametersUiState(
    val groups: List<ParameterGroup> = emptyList(),
    val values: Map<String, JsonPrimitive> = emptyMap(),
    /** Keys whose value was read from (or written to) the node, not a schema default. */
    val confirmed: Set<String> = emptySet(),
    /** Keys with a write in flight. */
    val saving: Set<String> = emptySet(),
    /** Camera roster for camera widgets; null while unknown. */
    val cameras: List<RosterCamera>? = null,
) {
    fun isVisible(p: PluginParameter): Boolean = ParameterRules.isVisible(p, values)
}

/** A write the node did not accept; the value was reverted. */
data class ParameterWriteError(val key: String, val message: String)

@HiltViewModel
class ExtensionParametersViewModel @Inject constructor(
    private val repository: ExtensionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ParametersUiState())
    val state: StateFlow<ParametersUiState> = _state.asStateFlow()

    private val errors = Channel<ParameterWriteError>(Channel.BUFFERED)
    val writeErrors: Flow<ParameterWriteError> = errors.receiveAsFlow()

    private var node: DeviceId? = null
    private var pluginId: String? = null
    private val touched = HashSet<String>()

    fun start(node: DeviceId, pluginId: String, parameters: List<PluginParameter>) {
        if (this.node == node && this.pluginId == pluginId) return
        this.node = node
        this.pluginId = pluginId
        touched.clear()
        _state.value = ParametersUiState(
            groups = group(parameters),
            values = parameters.associate { it.key to ParameterRules.defaultFor(it.schema) },
        )
        viewModelScope.launch {
            val live = (repository.config(node, pluginId) as? ApiResult.Ok)?.value ?: return@launch
            val read = parameters
                .filter { it.binding == ParameterBinding.PluginConfig && it.key !in touched }
                .mapNotNull { p -> live[p.key].scalar()?.let { p.key to it } }
                .toMap()
            if (read.isNotEmpty()) {
                _state.update { it.copy(values = it.values + read, confirmed = it.confirmed + read.keys) }
            }
        }
        if (parameters.any { ParameterRules.inferWidget(it.schema, it.ui) == ParameterWidget.Camera }) {
            viewModelScope.launch {
                val cams = (repository.api(node)?.cameras() as? ApiResult.Ok)?.value
                _state.update { it.copy(cameras = cams ?: emptyList()) }
            }
        }
    }

    /** Commit [raw] for [param]: clamp, validate, write; revert on refusal. */
    fun commit(param: PluginParameter, raw: JsonPrimitive) {
        val n = node ?: return
        val pid = pluginId ?: return
        if (param.binding != ParameterBinding.PluginConfig) return
        val value = ParameterRules.clamp(param.schema, raw)
        val check = ParameterRules.validateValue(param.schema, value)
        if (!check.ok) {
            errors.trySend(ParameterWriteError(param.key, check.error ?: "invalid"))
            return
        }
        val before = _state.value
        val previous = before.values[param.key]
        if (previous != null && ParameterRules.sameScalar(previous, value) && param.key in before.confirmed) return
        touched += param.key
        _state.update {
            it.copy(values = it.values + (param.key to value), confirmed = it.confirmed + param.key, saving = it.saving + param.key)
        }
        viewModelScope.launch {
            val outcome = repository.writeConfig(n, pid, param.key, value)
            _state.update { s ->
                if (outcome == WriteOutcome.Done) {
                    s.copy(saving = s.saving - param.key)
                } else {
                    s.copy(
                        values = if (previous != null) s.values + (param.key to previous) else s.values - param.key,
                        confirmed = if (param.key in before.confirmed) s.confirmed else s.confirmed - param.key,
                        saving = s.saving - param.key,
                    )
                }
            }
            when (outcome) {
                WriteOutcome.Done -> Unit
                is WriteOutcome.NoPath -> errors.send(ParameterWriteError(param.key, outcome.reasonKey))
                is WriteOutcome.Failed -> errors.send(ParameterWriteError(param.key, outcome.failure.toString()))
            }
        }
    }

    companion object {
        /** Group by `ui.group` in first-appearance order; sort by `ui.order`, then declaration. */
        fun group(parameters: List<PluginParameter>): List<ParameterGroup> {
            val order = LinkedHashMap<String?, MutableList<IndexedValue<PluginParameter>>>()
            parameters.withIndex().forEach { iv -> order.getOrPut(iv.value.ui?.group) { mutableListOf() } += iv }
            return order.map { (g, items) ->
                ParameterGroup(
                    g,
                    items.sortedWith(compareBy<IndexedValue<PluginParameter>> { it.value.ui?.order ?: Double.MAX_VALUE }.thenBy { it.index })
                        .map { it.value },
                )
            }
        }
    }
}
