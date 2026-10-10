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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface EntryDownload {
    data object None : EntryDownload
    data class Running(val receivedBytes: Long, val totalBytes: Long) : EntryDownload
    data class Failed(val reason: String) : EntryDownload
    data class Saved(val path: String) : EntryDownload
}

data class DataflashRow(val entry: LogEntry, val download: EntryDownload)

sealed interface DataflashUiState {
    data object NoVehicle : DataflashUiState
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

/** Lists and downloads the flight controller's onboard logs into app storage, indexed for Logs. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = DataflashViewModel.Factory::class)
class DataflashViewModel internal constructor(
    private val nodeId: DeviceId,
    logServices: Flow<LogService?>,
    private val files: TlogFileDao,
    private val dir: File,
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
        System::currentTimeMillis,
    )

    @AssistedFactory
    interface Factory {
        // A String id: KSP cannot name a factory method that takes an inline value class.
        fun create(nodeId: String): DataflashViewModel
    }

    private val service = logServices.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val listing = MutableStateFlow<Listing>(Listing.Pending)
    private val downloads = MutableStateFlow<Map<Int, EntryDownload>>(emptyMap())
    private val jobs = mutableMapOf<Int, Job>()

    val state: StateFlow<DataflashUiState> = combine(service, listing, downloads, files.observeAll()) { s, l, d, indexed ->
        val saved = indexed.filter { it.kind == LogFileKind.Dataflash && it.deviceId == nodeId.value && it.fcLogId != null }
            .associate { it.fcLogId!! to it.path }
        when {
            s == null -> DataflashUiState.NoVehicle
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
        viewModelScope.launch { service.collect { if (it != null) list(it) } }
    }

    fun refresh() {
        service.value?.let { s -> viewModelScope.launch { list(s) } }
    }

    private suspend fun list(s: LogService) {
        listing.value = Listing.Pending
        listing.value = when (val r = s.list()) {
            is LogListResult.Success -> Listing.Done(r.entries.sortedByDescending { it.id })
            is LogListResult.Failed -> Listing.Failed(r.reason)
        }
    }

    /** Downloads [entry]; a partial file from an earlier attempt resumes. */
    fun download(entry: LogEntry) {
        val s = service.value ?: return
        if (jobs[entry.id]?.isActive == true) return
        dir.mkdirs()
        val target = File(dir, "${nodeId.value}-log${entry.id}.bin")
        jobs[entry.id] = viewModelScope.launch {
            s.download(entry, target).collect { p ->
                val state = when (p) {
                    is LogDownloadProgress.Running -> EntryDownload.Running(p.receivedBytes, p.totalBytes)
                    is LogDownloadProgress.Failed -> EntryDownload.Failed(p.reason)
                    is LogDownloadProgress.Done -> {
                        files.insert(
                            TlogFileEntity(
                                path = p.file.absolutePath,
                                kind = LogFileKind.Dataflash,
                                sizeBytes = p.file.length(),
                                createdAtMs = if (entry.timeUtcS > 0) entry.timeUtcS * 1000 else now(),
                                deviceId = nodeId.value,
                                fcLogId = entry.id,
                            ),
                        )
                        EntryDownload.Saved(p.file.absolutePath)
                    }
                }
                downloads.update { it + (entry.id to state) }
            }
        }
    }

    /** Stops a running download; the partial file stays for a later resume. */
    fun cancel(entry: LogEntry) {
        jobs.remove(entry.id)?.cancel()
        downloads.update { it - entry.id }
    }
}
