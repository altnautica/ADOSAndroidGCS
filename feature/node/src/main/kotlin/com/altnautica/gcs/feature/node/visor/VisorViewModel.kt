package com.altnautica.gcs.feature.node.visor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.AgentEndpoints
import com.altnautica.gcs.core.agent.capture.CaptureApi
import com.altnautica.gcs.core.agent.capture.CaptureExposure
import com.altnautica.gcs.core.agent.capture.CaptureStartRequest
import com.altnautica.gcs.core.agent.capture.ReconstructResponse
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.link.direct.DemoNode
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.video.demo.DemoVideoFactory
import com.altnautica.gcs.feature.node.NodeTab
import com.altnautica.gcs.feature.node.common.NodeApis
import com.altnautica.gcs.feature.node.common.PageProblem
import com.altnautica.gcs.feature.node.groundstation.Load
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class VisorUiState(
    val capture: Load<CaptureUi> = Load.Loading,
    val sessions: Load<List<SessionUi>> = Load.Loading,
    val overview: Load<VisorOverview> = Load.Loading,
    val action: ActionState = ActionState.Idle,
    /** Segment number the agent gave the last MARK. */
    val lastSegment: Int? = null,
    /** Live camera preview; null when the node has no reachable video endpoint. */
    val preview: VideoSource? = null,
)

/**
 * The Visor tabs (Overview rows, Capture, Sessions). Only the visible tab
 * polls; every action runs one at a time and refreshes the tab on success.
 */
@HiltViewModel(assistedFactory = VisorViewModel.Factory::class)
class VisorViewModel @AssistedInject constructor(
    @Assisted nodeId: String,
    private val apis: NodeApis,
    private val endpoints: AgentEndpoints,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(nodeId: String): VisorViewModel
    }

    private val id = DeviceId(nodeId)
    private val _state = MutableStateFlow(VisorUiState())
    val state: StateFlow<VisorUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var activeTab: NodeTab? = null

    /** Starts polling [tab]'s data (stops the previous poll); null stops everything. */
    fun show(tab: NodeTab?) {
        if (tab == activeTab && pollJob?.isActive == true) return
        activeTab = tab
        pollJob?.cancel()
        val (period, loader) = when (tab) {
            NodeTab.Overview -> OVERVIEW_POLL_MS to ::loadOverview
            NodeTab.Capture -> CAPTURE_POLL_MS to ::loadCapture
            NodeTab.Sessions -> SESSIONS_POLL_MS to ::loadSessions
            else -> return
        }
        pollJob = viewModelScope.launch {
            if (tab == NodeTab.Capture) resolvePreview()
            while (isActive) {
                loader()
                delay(period)
            }
        }
    }

    // ---- loads ---------------------------------------------------------------

    internal suspend fun loadCapture() {
        val api = apis.capture(id) ?: return failCapture(PageProblem.NoAgentLink)
        when (val r = api.state()) {
            is AgentResult.Ok -> _state.update { it.copy(capture = Load.Ready(CaptureUi.from(r.value))) }
            is AgentResult.Err -> failCapture(PageProblem.from(r.error))
        }
    }

    internal suspend fun loadSessions() {
        val api = apis.capture(id) ?: return failSessions(PageProblem.NoAgentLink)
        when (val r = api.sessions()) {
            is AgentResult.Ok -> _state.update { it.copy(sessions = Load.Ready(r.value.sessions.map(SessionUi::from))) }
            is AgentResult.Err -> failSessions(PageProblem.from(r.error))
        }
    }

    internal suspend fun loadOverview() {
        val api = apis.sensors(id) ?: return _state.update { s ->
            if (s.overview is Load.Ready) s else s.copy(overview = Load.Failed(PageProblem.NoAgentLink))
        }
        val clock = api.clock()
        if (clock is AgentResult.Err) {
            return _state.update { s -> if (s.overview is Load.Ready) s else s.copy(overview = Load.Failed(PageProblem.from(clock.error))) }
        }
        val overview = VisorOverview(
            clock = clock.getOrNull()?.takeIf { it.present },
            storage = api.storage().getOrNull()?.takeIf { it.present },
            sensors = api.sensors().getOrNull()?.takeIf { it.present },
        )
        _state.update { it.copy(overview = Load.Ready(overview)) }
    }

    private suspend fun resolvePreview() {
        val source = if (id == DemoNode.VISOR_ID) {
            DemoVideoFactory.SOURCE
        } else {
            endpoints.endpointFor(id)?.let { VideoSource.AgentWhep(id, it.baseUrlString, null) }
        }
        _state.update { it.copy(preview = source) }
    }

    // ---- capture actions -----------------------------------------------------

    fun start(site: String?, floor: Int, room: String?) = act(CaptureAction.Start, refresh = ::loadCapture) { api ->
        api.start(CaptureStartRequest(site = site?.trim()?.ifEmpty { null }, floor = floor, room = room?.trim()?.ifEmpty { null }))
    }

    fun stop() = act(CaptureAction.Stop, refresh = ::loadCapture) { it.stop() }

    fun pause() = act(CaptureAction.Pause, refresh = ::loadCapture) { it.pause() }

    fun resume() = act(CaptureAction.Resume, refresh = ::loadCapture) { it.resume() }

    /** MARK: starts a new room segment named [room] on [floor]. */
    fun mark(room: String, floor: Int) {
        val name = room.trim()
        if (name.isEmpty()) return
        act(CaptureAction.Mark, refresh = ::loadCapture) { api ->
            api.mark(name, floor).also { r -> r.getOrNull()?.segment?.let { seg -> _state.update { it.copy(lastSegment = seg) } } }
        }
    }

    fun setExposure(exposure: CaptureExposure) = act(CaptureAction.Exposure, refresh = ::loadCapture) { it.exposure(exposure) }

    // ---- session actions -----------------------------------------------------

    fun upload(sessionId: String) = act(CaptureAction.Upload, refresh = ::loadSessions) { it.upload(sessionId) }

    fun reconstruct(sessionId: String, preset: ReconstructPreset) =
        act(CaptureAction.Reconstruct, refresh = ::loadSessions, detail = { (it as? ReconstructResponse)?.jobId }) {
            it.reconstruct(sessionId, preset.wire)
        }

    /** Deletes a session; [force] is the operator's explicit choice to drop one not yet uploaded. */
    fun delete(sessionId: String, force: Boolean) = act(CaptureAction.Delete, refresh = ::loadSessions) { it.delete(sessionId, force) }

    fun dismissAction() {
        val s = _state.value.action
        if (s is ActionState.Done || s is ActionState.Failed) _state.update { it.copy(action = ActionState.Idle) }
    }

    // ---- plumbing ------------------------------------------------------------

    private fun act(
        action: CaptureAction,
        refresh: suspend () -> Unit,
        detail: (Any?) -> String? = { null },
        block: suspend (CaptureApi) -> AgentResult<*>,
    ) {
        if (_state.value.action is ActionState.Running) return
        _state.update { it.copy(action = ActionState.Running(action)) }
        viewModelScope.launch {
            val api = apis.capture(id)
            val result = if (api == null) null else block(api)
            val next = when (result) {
                null -> ActionState.Failed(action, CaptureProblem.Page(PageProblem.NoAgentLink))
                is AgentResult.Ok -> ActionState.Done(action, detail(result.value))
                is AgentResult.Err -> ActionState.Failed(action, CaptureProblem.from(result.error))
            }
            _state.update { it.copy(action = next) }
            if (result is AgentResult.Ok) refresh()
        }
    }

    private fun failCapture(problem: PageProblem) = _state.update { s ->
        if (s.capture is Load.Ready) s else s.copy(capture = Load.Failed(problem))
    }

    private fun failSessions(problem: PageProblem) = _state.update { s ->
        if (s.sessions is Load.Ready) s else s.copy(sessions = Load.Failed(problem))
    }

    private companion object {
        const val CAPTURE_POLL_MS = 1_000L
        const val SESSIONS_POLL_MS = 3_000L
        const val OVERVIEW_POLL_MS = 3_000L
    }
}
