package com.altnautica.gcs.feature.logs.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.database.FlightSessionDao
import com.altnautica.gcs.core.database.FlightSessionEntity
import com.altnautica.gcs.core.database.LogFileKind
import com.altnautica.gcs.core.database.TlogFileDao
import com.altnautica.gcs.feature.logs.tlog.FlightSeries
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

sealed interface SeriesState {
    data object Loading : SeriesState
    data object NoLog : SeriesState
    data class Failed(val message: String) : SeriesState
    data class Ready(val series: FlightSeries, val tlogPath: String) : SeriesState
}

sealed interface FlightDetailUiState {
    data object Loading : FlightDetailUiState
    data object NotFound : FlightDetailUiState
    data class Content(val session: FlightSessionEntity, val series: SeriesState) : FlightDetailUiState
}

@HiltViewModel(assistedFactory = FlightDetailViewModel.Factory::class)
class FlightDetailViewModel internal constructor(
    private val sessionId: Long,
    private val sessions: FlightSessionDao,
    private val files: TlogFileDao,
    private val io: CoroutineDispatcher,
    private val open: (String) -> InputStream,
) : ViewModel() {
    @AssistedInject constructor(
        @Assisted sessionId: Long,
        sessions: FlightSessionDao,
        files: TlogFileDao,
    ) : this(sessionId, sessions, files, Dispatchers.IO, { File(it).inputStream() })

    @AssistedFactory
    interface Factory {
        fun create(sessionId: Long): FlightDetailViewModel
    }

    private val _state = MutableStateFlow<FlightDetailUiState>(FlightDetailUiState.Loading)
    val state: StateFlow<FlightDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = FlightDetailUiState.Loading
            val session = withContext(io) { sessions.getById(sessionId) }
            if (session == null) {
                _state.value = FlightDetailUiState.NotFound
                return@launch
            }
            val path = withContext(io) {
                files.listForSession(sessionId).firstOrNull { it.kind == LogFileKind.Tlog }?.path
                    ?: session.tlogPath.takeIf { it.isNotEmpty() }
            }
            if (path == null) {
                _state.value = FlightDetailUiState.Content(session, SeriesState.NoLog)
                return@launch
            }
            _state.value = FlightDetailUiState.Content(session, SeriesState.Loading)
            val series = withContext(io) {
                runCatching { open(path).use { FlightSeries.read(it) } }
            }
            _state.value = FlightDetailUiState.Content(
                session,
                series.fold(
                    onSuccess = { SeriesState.Ready(it, path) },
                    onFailure = { SeriesState.Failed(it.message ?: it.javaClass.simpleName) },
                ),
            )
        }
    }
}
