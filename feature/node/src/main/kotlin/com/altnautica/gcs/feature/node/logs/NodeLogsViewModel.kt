package com.altnautica.gcs.feature.node.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.fleet.data.NodeRepository
import com.altnautica.gcs.feature.node.common.NodeClients
import com.altnautica.gcs.feature.node.common.PageProblem
import com.altnautica.gcs.feature.node.common.arr
import com.altnautica.gcs.feature.node.common.int
import com.altnautica.gcs.feature.node.common.objects
import com.altnautica.gcs.feature.node.common.str
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

data class LogEntry(val seq: Long?, val timestamp: String?, val level: String, val logger: String, val message: String)

enum class LogLevel(val wire: String?) { All(null), Debug("DEBUG"), Info("INFO"), Warning("WARN"), Error("ERROR") }

data class NodeLogsUiState(
    val level: LogLevel = LogLevel.All,
    val service: String = "",
    val entries: List<LogEntry> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val problem: PageProblem? = null,
    /** The agent's log store is off or unreachable (`warning` in a 200 answer). */
    val storeWarning: String? = null,
) {
    val canLoadMore: Boolean get() = !loading && entries.size < total
}

/** Agent logs for one node: `GET /api/logs?level=&service=&limit=&offset=`, newest first. */
@HiltViewModel(assistedFactory = NodeLogsViewModel.Factory::class)
class NodeLogsViewModel @AssistedInject constructor(
    @Assisted nodeId: String,
    private val nodes: NodeRepository,
    private val clients: NodeClients,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(nodeId: String): NodeLogsViewModel
    }

    private val id = DeviceId(nodeId)
    private val _state = MutableStateFlow(NodeLogsUiState())
    val state: StateFlow<NodeLogsUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        load(reset = true)
    }

    fun setLevel(level: LogLevel) {
        _state.update { it.copy(level = level) }
        load(reset = true)
    }

    fun setService(service: String) {
        _state.update { it.copy(service = service) }
    }

    fun applyFilter() = load(reset = true)

    fun refresh() = load(reset = true)

    fun loadMore() {
        if (_state.value.canLoadMore) load(reset = false)
    }

    private fun load(reset: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, problem = null) }
            val s = _state.value
            val client = clients.clientFor(nodes.node(id).filterNotNull().first())
            if (client == null) {
                _state.update { it.copy(loading = false, problem = PageProblem.NoAgentLink) }
                return@launch
            }
            val offset = if (reset) 0 else s.entries.size
            val query = mapOf(
                "level" to s.level.wire,
                "service" to s.service.trim().ifEmpty { null },
                "limit" to PAGE.toString(),
                "offset" to offset.toString(),
            )
            when (val r = client.requestJson("GET", "api/logs", query = query)) {
                is AgentResult.Ok -> {
                    val body = r.value as? JsonObject ?: JsonObject(emptyMap())
                    val page = parse(body)
                    _state.update {
                        it.copy(
                            entries = if (reset) page else it.entries + page,
                            total = body.int("total") ?: page.size,
                            loading = false,
                            storeWarning = body.str("warning"),
                        )
                    }
                }
                is AgentResult.Err -> _state.update { it.copy(loading = false, problem = PageProblem.from(r.error)) }
            }
        }
    }

    companion object {
        const val PAGE = 100

        fun parse(body: JsonObject): List<LogEntry> = body.arr("entries")?.objects().orEmpty().map { e ->
            LogEntry(
                seq = e.int("seq")?.toLong(),
                timestamp = e.str("timestamp"),
                level = e.str("level")?.uppercase() ?: "INFO",
                logger = e.str("logger").orEmpty(),
                message = e.str("message").orEmpty(),
            )
        }
    }
}
