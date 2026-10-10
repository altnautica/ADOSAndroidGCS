package com.altnautica.gcs.core.extensions.host

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.extensions.bridge.ExtensionEventBus
import com.altnautica.gcs.core.extensions.bridge.ExtensionHandlersFactory
import com.altnautica.gcs.core.extensions.bridge.FrameShell
import com.altnautica.gcs.core.extensions.data.ApiResult
import com.altnautica.gcs.core.extensions.data.ExtensionFailure
import com.altnautica.gcs.core.extensions.data.ExtensionRepository
import com.altnautica.gcs.core.extensions.data.ExtensionsState
import com.altnautica.gcs.core.extensions.manifest.ExtensionDetail
import com.altnautica.gcs.core.extensions.manifest.GcsIsolation
import com.altnautica.gcs.core.model.DeviceId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import javax.inject.Inject

/** What an extension frame can show. */
sealed interface FrameState {
    data object Loading : FrameState
    /** Frame document ready to mount. */
    data class Ready(val html: String) : FrameState
    /** An inline-isolation module: only the desktop app can run it. */
    data object InlineOnly : FrameState
    /** The extension ships no GCS bundle. */
    data object NoBundle : FrameState
    data class Failed(val failure: ExtensionFailure) : FrameState
}

/** One mounted extension frame: its bundle, bridge session and host events. */
@HiltViewModel
class ExtensionFrameViewModel @Inject constructor(
    private val repository: ExtensionRepository,
    private val handlers: ExtensionHandlersFactory,
    private val bus: ExtensionEventBus,
) : ViewModel() {

    private val _state = MutableStateFlow<FrameState>(FrameState.Loading)
    val state: StateFlow<FrameState> = _state.asStateFlow()

    var session: ExtensionFrameSession? = null
        private set

    private var started = false
    @Volatile private var granted: Set<String> = emptySet()

    fun start(node: DeviceId, pluginId: String) {
        if (started) return
        started = true
        val mountId = UUID.randomUUID().toString()
        session = ExtensionFrameSession(
            pluginId = pluginId,
            handlers = handlers.create(pluginId, node, mountId),
            granted = { granted },
            bus = bus,
            agentStateOrigin = ExtensionEventBus.agentStateOrigin(pluginId, node),
            scope = viewModelScope,
        )
        viewModelScope.launch {
            repository.extensions(node)
                .mapNotNull { s -> (s as? ExtensionsState.Loaded)?.extensions?.firstOrNull { it.pluginId == pluginId } }
                .collect { granted = it.grantedCapabilities }
        }
        viewModelScope.launch {
            if (repository.extensions(node).value !is ExtensionsState.Loaded) repository.refresh(node)
            val detail = repository.extensions(node)
                .mapNotNull { (it as? ExtensionsState.Loaded)?.extensions?.firstOrNull { e -> e.pluginId == pluginId } }
                .first()
            _state.value = load(node, detail)
        }
        viewModelScope.launch {
            repository.config(node, pluginId)
            repository.knownConfig(node, pluginId).filterNotNull().collect { cfg ->
                session?.hostEvent("config.changed", "", cfg)
            }
        }
    }

    private suspend fun load(node: DeviceId, detail: ExtensionDetail): FrameState {
        if (detail.isolation == GcsIsolation.Inline) return FrameState.InlineOnly
        val entry = detail.entrypoint ?: return FrameState.NoBundle
        val api = repository.api(node) ?: return FrameState.Failed(ExtensionFailure.FeatureAbsent)
        return when (val r = api.gcsAsset(detail.pluginId, entry.removePrefix("gcs/"))) {
            is ApiResult.Ok -> FrameState.Ready(FrameShell.build(r.value.decodeToString()))
            is ApiResult.Err -> FrameState.Failed(r.failure)
        }
    }

    fun setTheme(vars: Map<String, String>) {
        session?.hostEvent("theme.changed", "theme.useTheme", JsonObject(vars.mapValues { JsonPrimitive(it.value) }))
    }

    fun setHostEvent(method: String, args: JsonElement) {
        session?.hostEvent(method, "", args)
    }

    override fun onCleared() {
        session?.dispose()
        session = null
    }
}
