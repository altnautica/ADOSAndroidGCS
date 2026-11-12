package com.altnautica.gcs.feature.node.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.feature.node.common.AgentWriteRunner
import com.altnautica.gcs.feature.node.common.NodeClients
import com.altnautica.gcs.feature.node.common.PageProblem
import com.altnautica.gcs.feature.node.common.NO_AGENT_LINK
import com.altnautica.gcs.feature.node.common.WriteAction
import com.altnautica.gcs.feature.node.common.WriteLabel
import com.altnautica.gcs.feature.node.common.WriteState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface AgentConfigState {
    data object Loading : AgentConfigState
    data class Problem(val problem: PageProblem) : AgentConfigState
    data class Ready(val schema: ConfigSchema, val config: JsonObject) : AgentConfigState
}

sealed interface LiveState {
    data object Loading : LiveState
    /** [stale]: the latest poll failed and [value] is the last good reading. */
    data class Ready(val value: JsonElement, val stale: Boolean = false) : LiveState

    /** The agent does not serve this readout (feature absent or other profile). */
    data object Absent : LiveState
    data class Failed(val problem: PageProblem) : LiveState
}

data class AgentPageUiState(
    val profile: NodeProfile = NodeProfile.Unknown,
    val config: AgentConfigState = AgentConfigState.Loading,
    val live: Map<SectionLive, LiveState> = emptyMap(),
    /** Input the agent's schema rejected locally, by config key. */
    val fieldErrors: Map<String, ConfigValueCodec.Reason> = emptyMap(),
)

/**
 * The node's Agent page: typed section forms and the generic config tree, both
 * over `GET /api/config/schema` + `GET /api/config`, written one key at a time
 * with `PUT /api/config {key, value}` (armed override via the force sheet).
 */
@HiltViewModel(assistedFactory = AgentPageViewModel.Factory::class)
class AgentPageViewModel @AssistedInject constructor(
    @Assisted private val nodeId: String,
    private val nodes: NodeRepository,
    private val clients: NodeClients,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(nodeId: String): AgentPageViewModel
    }

    private val _state = MutableStateFlow(AgentPageUiState())
    val state: StateFlow<AgentPageUiState> = _state.asStateFlow()

    val writes = AgentWriteRunner(viewModelScope)
    val writeState: StateFlow<WriteState> get() = writes.state

    private var loadJob: Job? = null
    private val liveJobs = mutableMapOf<SectionLive, Job>()

    init {
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val node = node()
            _state.update { it.copy(profile = node.profile) }
            val client = clients.clientFor(node)
            if (client == null) {
                _state.update { it.copy(config = AgentConfigState.Problem(PageProblem.NoAgentLink)) }
                return@launch
            }
            if (_state.value.config !is AgentConfigState.Ready) _state.update { it.copy(config = AgentConfigState.Loading) }
            _state.update { it.copy(config = loadConfig(client)) }
        }
    }

    /** Polls the live readouts of a section every [LIVE_POLL_MS] while it is open. */
    fun openSection(section: AgentSection) {
        closeSection()
        section.live.filter { it != SectionLive.None }.forEach { loadLive(it, poll = true) }
    }

    /** Stops the open section's live polls. */
    fun closeSection() {
        liveJobs.values.forEach { it.cancel() }
        liveJobs.clear()
    }

    fun refreshLive(kind: SectionLive) = loadLive(kind, poll = liveJobs[kind]?.isActive == true)

    /** Validates [input] against the schema, then writes it. */
    fun write(key: String, input: String) {
        val ready = _state.value.config as? AgentConfigState.Ready ?: return
        val leaf = ready.schema.leaf(key) ?: return
        when (val parsed = ConfigValueCodec.encode(leaf, input)) {
            is ConfigValueCodec.Parsed.Invalid -> _state.update { it.copy(fieldErrors = it.fieldErrors + (key to parsed.reason)) }
            is ConfigValueCodec.Parsed.Ok -> {
                _state.update { it.copy(fieldErrors = it.fieldErrors - key) }
                writes.run(label = WriteLabel(WriteAction.Setting, leaf.title), onDone = ::reloadConfigQuietly) { force ->
                    val client = clients.clientFor(node()) ?: return@run AgentResult.Err(NO_AGENT_LINK)
                    client.putConfig(key, parsed.value, force)
                }
            }
        }
    }

    fun writeBool(key: String, value: Boolean) = write(key, value.toString())

    /** Restarts a service; the page asks first (see the restart confirm). */
    fun restartService(name: String) {
        writes.run(label = WriteLabel(WriteAction.RestartService, name), onDone = { refreshLive(SectionLive.Services) }) { force ->
            val client = clients.clientFor(node()) ?: return@run AgentResult.Err(NO_AGENT_LINK)
            client.restartService(name, force)
        }
    }

    private fun reloadConfigQuietly() {
        viewModelScope.launch {
            val client = clients.clientFor(node()) ?: return@launch
            val config = client.config()
            if (config is AgentResult.Ok) {
                _state.update { s ->
                    val ready = s.config as? AgentConfigState.Ready ?: return@update s
                    s.copy(config = ready.copy(config = config.value))
                }
            }
        }
    }

    private suspend fun loadConfig(client: AgentClient): AgentConfigState {
        val schema = when (val r = client.configSchema()) {
            is AgentResult.Ok -> ConfigSchema.parse(r.value)
            is AgentResult.Err -> return AgentConfigState.Problem(PageProblem.from(r.error))
        }
        return when (val r = client.config()) {
            is AgentResult.Ok -> AgentConfigState.Ready(schema, r.value)
            is AgentResult.Err -> AgentConfigState.Problem(PageProblem.from(r.error))
        }
    }

    private fun loadLive(kind: SectionLive, poll: Boolean = false) {
        if (kind == SectionLive.None) return
        liveJobs[kind]?.cancel()
        liveJobs[kind] = viewModelScope.launch {
            _state.update { it.copy(live = it.live + (kind to LiveState.Loading)) }
            do {
                val result = liveAfter(_state.value.live[kind], fetchLive(kind))
                _state.update { it.copy(live = it.live + (kind to result)) }
                if (poll) delay(LIVE_POLL_MS)
            } while (poll && result != LiveState.Absent)
        }
    }

    private suspend fun fetchLive(kind: SectionLive): AgentResult<JsonElement>? {
        val client = clients.clientFor(node()) ?: return null
        return when (kind) {
            SectionLive.SystemResources -> client.system().widen()
            SectionLive.Battery -> client.requestJson("GET", "api/v1/battery")
            SectionLive.WfbLink -> client.wfb().widen()
            SectionLive.CameraRoster -> client.requestJson("GET", "api/video/roster")
            SectionLive.Services -> client.services()
            SectionLive.None -> error("unreachable")
        }
    }

    private suspend fun node(): NodeSummary = nodes.node(DeviceId(nodeId)).filterNotNull().first()

    private fun <T : JsonElement> AgentResult<T>.widen(): AgentResult<JsonElement> = when (this) {
        is AgentResult.Ok -> AgentResult.Ok(value)
        is AgentResult.Err -> this
    }

    companion object {
        const val LIVE_POLL_MS = 3_000L

        /** The next live state after a read; a failed poll keeps the last good reading, marked stale. */
        internal fun liveAfter(last: LiveState?, r: AgentResult<JsonElement>?): LiveState = when (r) {
            null -> if (last is LiveState.Ready) last.copy(stale = true) else LiveState.Failed(PageProblem.NoAgentLink)
            is AgentResult.Ok -> LiveState.Ready(r.value)
            is AgentResult.Err -> when (val p = PageProblem.from(r.error)) {
                PageProblem.Unsupported -> LiveState.Absent
                else -> if (last is LiveState.Ready) last.copy(stale = true) else LiveState.Failed(p)
            }
        }

        /** Value shown for a config key: the current config value at that dotted path. */
        fun valueAt(config: JsonObject, key: String): JsonElement? {
            var cur: JsonElement = config
            for (s in key.split('.')) cur = (cur as? JsonObject)?.get(s) ?: return null
            return cur
        }

        internal fun redacted(value: JsonElement?): Boolean = (value as? JsonPrimitive)?.content == "***"
    }
}
