package com.altnautica.gcs.feature.logs.dataflash

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.database.LogFileKind
import com.altnautica.gcs.core.database.TlogFileDao
import com.altnautica.gcs.core.database.TlogFileEntity
import com.altnautica.gcs.core.link.log.LogDownloadProgress
import com.altnautica.gcs.core.link.log.LogEntry
import com.altnautica.gcs.core.link.log.LogListResult
import com.altnautica.gcs.core.link.log.LogService
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.model.DeviceId
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface EntryDownload {
    data object None : EntryDownload

    /** Waiting for the download ahead of it: the flight controller serves one at a time. */
    data object Queued : EntryDownload
    data class Running(val receivedBytes: Long, val totalBytes: Long) : EntryDownload
    data class Failed(val reason: String) : EntryDownload
    data class Saved(val path: String) : EntryDownload
}

data class DataflashRow(val entry: LogEntry, val download: EntryDownload)

sealed interface DataflashUiState {
    data object NoVehicle : DataflashUiState

    /** The vehicle is armed: logs are neither listed nor downloaded until it disarms. */
    data object Armed : DataflashUiState
    data object Listing : DataflashUiState
    data class ListFailed(val reason: String) : DataflashUiState
    data object Empty : DataflashUiState
    data class Content(val rows: List<DataflashRow>) : DataflashUiState
}

private sealed interface Listing {
    data object Pending : Listing
    data class Failed(val reason: String) : Listing
    data class Done(val entries: List<LogEntry>) : Listing
}

/**
 * Lists and downloads the flight controller's onboard logs into app storage, indexed for Logs.
 * Downloads run one at a time from a queue (a second `LOG_REQUEST_DATA` stream would end the
 * first), and nothing is listed or downloaded while the vehicle is armed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = DataflashViewModel.Factory::class)
class DataflashViewModel internal constructor(
    private val nodeId: DeviceId,
    logServices: Flow<LogService?>,
    private val files: TlogFileDao,
    private val dir: File,
    armedFlow: Flow<Boolean> = flowOf(false),
    private val now: () -> Long,
) : ViewModel() {
    @AssistedInject constructor(
        @Assisted nodeId: String,
        services: VehicleServicesProvider,
        files: TlogFileDao,
        @ApplicationContext context: Context,
    ) : this(
        DeviceId(nodeId),
        services.services(DeviceId(nodeId)).map { it?.logs },
        files,
        File(context.filesDir, "dataflash"),
        services.services(DeviceId(nodeId)).flatMapLatest { svc ->
            svc?.vehicle?.telemetry?.heartbeat?.map { it?.armed == true } ?: flowOf(false)
        },
        System::currentTimeMillis,
    )

    @AssistedFactory
    interface Factory {
        // A String id: KSP cannot name a factory method that takes an inline value class.
        fun create(nodeId: String): DataflashViewModel
    }

    private val service = logServices.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val armed = armedFlow.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val listing = MutableStateFlow<Listing>(Listing.Pending)
    private val downloads = MutableStateFlow<Map<Int, EntryDownload>>(emptyMap())

    /** Entries waiting to download, in request order; the head is the running one. */
    private val queue = ArrayDeque<LogEntry>()
    private var worker: Job? = null

    val state: StateFlow<DataflashUiState> = combine(service, armed, listing, downloads, files.observeAll()) { s, a, l, d, indexed ->
        val saved = indexed.filter { it.kind == LogFileKind.Dataflash && it.deviceId == nodeId.value && it.fcLogId != null }
            .associate { it.fcLogId!! to it.path }
        when {
            s == null -> DataflashUiState.NoVehicle
            a -> DataflashUiState.Armed
            l is Listing.Pending -> DataflashUiState.Listing
            l is Listing.Failed -> DataflashUiState.ListFailed(l.reason)
            l is Listing.Done && l.entries.isEmpty() -> DataflashUiState.Empty
            else -> DataflashUiState.Content(
                (l as Listing.Done).entries.map { e ->
                    DataflashRow(e, d[e.id] ?: saved[e.id]?.let { EntryDownload.Saved(it) } ?: EntryDownload.None)
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DataflashUiState.NoVehicle)

    init {
        viewModelScope.launch {
            combine(service, armed) { s, a -> s to a }.collect { (s, a) ->
                if (a) {
                    stopAll()
                } else if (s != null) {
                    list(s)
                }
            }
        }
    }

    fun refresh() {
        if (armed.value) return
        service.value?.let { s -> viewModelScope.launch { list(s) } }
    }

    private suspend fun list(s: LogService) {
        listing.value = Listing.Pending
        listing.value = when (val r = s.list()) {
            is LogListResult.Success -> Listing.Done(r.entries.sortedByDescending { it.id })
            is LogListResult.Failed -> Listing.Failed(r.reason)
        }
    }

    /** Queues [entry]; downloads run one at a time, and a partial file from an earlier attempt resumes. */
    fun download(entry: LogEntry) {
        if (service.value == null || armed.value) return
        if (queue.any { it.id == entry.id }) return
        queue.addLast(entry)
        downloads.update { it + (entry.id to EntryDownload.Queued) }
        if (worker?.isActive != true) worker = viewModelScope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            val entry = queue.firstOrNull() ?: return
            val s = service.value ?: return
            run(s, entry)
            queue.removeFirstOrNull()
        }
    }

    private suspend fun run(s: LogService, entry: LogEntry) {
        dir.mkdirs()
        val target = File(dir, "${nodeId.value}-log${entry.id}.bin")
        s.download(entry, target).collect { p ->
            val state = when (p) {
                is LogDownloadProgress.Running -> EntryDownload.Running(p.receivedBytes, p.totalBytes)
                is LogDownloadProgress.Failed -> EntryDownload.Failed(p.reason)
                is LogDownloadProgress.Done -> {
                    val file = LogFileType.withSniffedExtension(p.file)
                    files.insert(
                        TlogFileEntity(
                            path = file.absolutePath,
                            kind = LogFileKind.Dataflash,
                            sizeBytes = file.length(),
                            createdAtMs = if (entry.timeUtcS > 0) entry.timeUtcS * 1000 else now(),
                            deviceId = nodeId.value,
                            fcLogId = entry.id,
                        ),
                    )
                    EntryDownload.Saved(file.absolutePath)
                }
            }
            downloads.update { it + (entry.id to state) }
        }
    }

    /** Stops [entry]: drops it from the queue, or ends its transfer; a partial file stays for a later resume. */
    fun cancel(entry: LogEntry) {
        val running = queue.firstOrNull()?.id == entry.id
        queue.removeAll { it.id == entry.id }
        downloads.update { it - entry.id }
        if (running) {
            worker?.cancel()
            worker = if (queue.isNotEmpty()) viewModelScope.launch { drain() } else null
        }
    }

    /** The saved file at [path] is gone (Share found nothing): drops its index row so the entry downloads again. */
    fun fileMissing(path: String) {
        downloads.update { d -> d.filterValues { it != EntryDownload.Saved(path) } }
        viewModelScope.launch {
            files.observeAll().first().filter { it.path == path }.forEach { files.delete(it.id) }
        }
    }

    /** Arming stops every transfer and empties the queue; partial files stay for a resume. */
    private fun stopAll() {
        worker?.cancel()
        worker = null
        val stopped = queue.map { it.id }.toSet()
        queue.clear()
        downloads.update { d -> d - stopped }
    }
}
