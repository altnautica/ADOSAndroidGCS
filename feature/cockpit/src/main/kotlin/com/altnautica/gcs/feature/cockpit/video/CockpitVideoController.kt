package com.altnautica.gcs.feature.cockpit.video

import com.altnautica.gcs.core.agent.AgentEndpoints
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.video.StreamCatalog
import com.altnautica.gcs.core.agent.video.StreamSwitchOutcome
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.StreamDescriptor
import com.altnautica.gcs.core.model.StreamSwitchKind
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.RecordingState
import com.altnautica.gcs.core.video.VideoSessionManager
import com.altnautica.gcs.core.video.VideoSourceSelector
import com.altnautica.gcs.feature.cockpit.CockpitEvent
import com.altnautica.gcs.feature.cockpit.UI_STOP_MS
import com.altnautica.gcs.feature.cockpit.VideoUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cockpit video: source selection with failover, stream tabs and switching,
 * picture-in-picture and recording for the flown vehicle's feed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CockpitVideoController(
    private val scope: CoroutineScope,
    vehicleNode: StateFlow<NodeSummary?>,
    private val videoSelector: VideoSourceSelector,
    private val videoSessions: VideoSessionManager,
    private val endpoints: AgentEndpoints,
    private val streamCatalog: StreamCatalog,
    private val events: MutableSharedFlow<CockpitEvent>,
) {
    private val baseUrls: StateFlow<Map<DeviceId, String>> = vehicleNode.map { n ->
        if (n == null) return@map emptyMap()
        val ids = listOf(n.id) + n.reaches.filterIsInstance<Reach.ViaGround>().map { it.groundId }
        ids.mapNotNull { id -> endpoints.endpointFor(id)?.let { id to it.baseUrl.toString() } }.toMap()
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val selectedStream = MutableStateFlow<String?>(null)
    private val switchingStream = MutableStateFlow<String?>(null)
    private val failedSources = MutableStateFlow<Set<VideoSource>>(emptySet())
    private val pipVisible = MutableStateFlow(false)

    /** The leg the PiP shows when it can: the one swapped out of the main view. */
    private val pipPreferred = MutableStateFlow<String?>(null)

    private val candidates: StateFlow<List<VideoSource>> = combine(vehicleNode, baseUrls, selectedStream) { n, urls, stream ->
        if (n == null) emptyList() else videoSelector.candidates(n, stream) { urls[it] }.filter(videoSessions::canPlay)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** First candidate that has not failed; on failure the next one is tried. */
    private val mainSource: StateFlow<VideoSource?> = combine(candidates, failedSources) { list, failed ->
        list.firstOrNull { it !in failed } ?: list.firstOrNull()
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    val videoState: StateFlow<VideoState> = mainSource.flatMapLatest { src ->
        if (src == null) flowOf(VideoState.NoSource) else videoSessions.state(src)
    }.stateIn(scope, SharingStarted.Eagerly, VideoState.NoSource)

    private val streams: StateFlow<List<StreamDescriptor>> = mainSource.flatMapLatest { src ->
        if (src is VideoSource.AgentWhep) streamCatalog.streams(src.node) else flowOf(emptyList())
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val selection = combine(selectedStream, pipPreferred) { s, p -> s to p }

    val video: StateFlow<VideoUi> = combine(mainSource, streams, selection, switchingStream, pipVisible) { main, list, (sel, pref), switching, pip ->
        val selected = sel ?: list.firstOrNull { it.live }?.id
        val pipStream = if (pip && main is VideoSource.AgentWhep) pickPip(list, selected, pref) else null
        VideoUi(
            main = main,
            pip = pipStream?.let { (main as VideoSource.AgentWhep).copy(streamId = it.id) },
            streams = list,
            selectedStreamId = selected,
            switchingStreamId = switching,
        )
    }.stateIn(scope, SharingStarted.Eagerly, VideoUi(null, null, emptyList(), null, null))

    init {
        scope.launch {
            combine(mainSource, videoState) { src, state -> src to state }.collect { (src, state) ->
                if (src != null && state is VideoState.Failed && candidates.value.any { it !in failedSources.value && it != src }) {
                    failedSources.update { it + src }
                }
            }
        }
    }

    /**
     * Dials the video again after it failed: every source is eligible again, and the
     * session of the source on screen restarts from scratch.
     */
    fun retry() {
        val src = mainSource.value
        failedSources.value = emptySet()
        val handle = src?.let(videoSessions::running) ?: return
        scope.launch {
            handle.stop()
            handle.start()
        }
    }

    private var switchJob: Job? = null

    fun selectStream(streamId: String) {
        val list = streams.value
        val target = list.firstOrNull { it.id == streamId } ?: return
        val node = (mainSource.value as? VideoSource.AgentWhep)?.node ?: return
        switchJob?.cancel()
        switchJob = null
        switchingStream.value = null
        val previous = video.value.selectedStreamId
        if (target.id == previous) return
        if (target.switchKind == StreamSwitchKind.Concurrent) {
            selectedStream.value = target.id
            return
        }
        switchingStream.value = target.id
        switchJob = scope.launch {
            val result = withTimeoutOrNull(SWITCH_TIMEOUT_MS) { streamCatalog.switch(node, target.id) }
            ensureActive()
            switchingStream.value = null
            when (result) {
                is AgentResult.Ok -> selectedStream.value = when (val o = result.value) {
                    is StreamSwitchOutcome.UseCameraQuery -> o.cameraId
                    StreamSwitchOutcome.Switched -> target.id
                }
                else -> {
                    selectedStream.value = previous
                    events.tryEmit(CockpitEvent.StreamSwitchFailed)
                }
            }
        }
    }

    fun cycleStream(delta: Int) {
        val list = streams.value.filter { it.live }
        if (list.size < 2) return
        val current = list.indexOfFirst { it.id == video.value.selectedStreamId }.coerceAtLeast(0)
        selectStream(list[(current + delta).mod(list.size)].id)
    }

    /** Swaps the main video and the PiP: the PiP leg becomes main and the old main goes into the PiP. */
    fun swapPip() {
        val v = video.value
        val pipId = (v.pip as? VideoSource.AgentWhep)?.streamId ?: return
        pipPreferred.value = v.selectedStreamId
        selectStream(pipId)
    }

    fun selectStreamIndex(index: Int) {
        streams.value.getOrNull(index)?.let { selectStream(it.id) }
    }

    fun togglePip() {
        pipVisible.update { !it }
    }

    val recording: StateFlow<RecordingState?> = mainSource.flatMapLatest { src ->
        if (src == null) flowOf(null)
        else videoSessions.state(src).map { videoSessions.running(src)?.recording }.distinctUntilChanged()
            .flatMapLatest { rec -> rec?.state ?: flowOf(null) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** True when the current feed can record. */
    val canRecord: StateFlow<Boolean> = recording.map { it != null }.stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), false)

    /** True while the current feed records; quick settings read this rather than the whole band. */
    val recordingActive: StateFlow<Boolean> = recording.map { it is RecordingState.Recording }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), false)

    fun toggleRecording() {
        val src = mainSource.value ?: return
        val rec = videoSessions.running(src)?.recording ?: return
        scope.launch {
            val result = if (rec.state.value is RecordingState.Recording) rec.stop() else rec.start()
            if (result.isFailure) events.tryEmit(CockpitEvent.RecordingFailed)
        }
    }

    /** Cancels an in-flight stream switch. */
    fun cancel() {
        switchJob?.cancel()
    }

    private companion object {
        const val SWITCH_TIMEOUT_MS = 3_000L
    }
}

/**
 * The PiP leg: [preferred] (the leg last swapped out of the main view) when it is a live
 * concurrent leg other than [selected], else the first such leg.
 */
internal fun pickPip(list: List<StreamDescriptor>, selected: String?, preferred: String?): StreamDescriptor? {
    val eligible = list.filter { it.id != selected && it.live && it.switchKind == StreamSwitchKind.Concurrent }
    return eligible.firstOrNull { it.id == preferred } ?: eligible.firstOrNull()
}
