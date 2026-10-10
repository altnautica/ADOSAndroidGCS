package com.altnautica.gcs.feature.logs.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.database.FlightSessionDao
import com.altnautica.gcs.core.database.FlightSessionEntity
import com.altnautica.gcs.core.database.LogFileKind
import com.altnautica.gcs.core.database.TlogFileDao
import com.altnautica.gcs.core.database.TlogFileEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class FlightRow(val session: FlightSessionEntity, val hasTlog: Boolean)

sealed interface FlightHistoryUiState {
    data object Loading : FlightHistoryUiState
    data object Empty : FlightHistoryUiState
    data class Error(val message: String) : FlightHistoryUiState
    data class Content(val flights: List<FlightRow>, val dataflashLogs: List<TlogFileEntity>) : FlightHistoryUiState
}

@HiltViewModel
class FlightHistoryViewModel internal constructor(
    private val sessions: FlightSessionDao,
    private val files: TlogFileDao,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(sessions: FlightSessionDao, files: TlogFileDao) : this(sessions, files, Dispatchers.IO)

    private val reload = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<FlightHistoryUiState> = reload.flatMapLatest {
        combine(sessions.getAll(), files.observeAll()) { flights, logs ->
            val tlogSessions = logs.filter { it.kind == LogFileKind.Tlog }.mapNotNull { it.sessionId }.toSet()
            val dataflash = logs.filter { it.kind == LogFileKind.Dataflash }
            if (flights.isEmpty() && dataflash.isEmpty()) {
                FlightHistoryUiState.Empty
            } else {
                FlightHistoryUiState.Content(
                    flights.map { FlightRow(it, it.id in tlogSessions) },
                    dataflash,
                )
            }
        }.catch<FlightHistoryUiState> { emit(FlightHistoryUiState.Error(it.message.orEmpty())) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlightHistoryUiState.Loading)

    fun retry() {
        reload.value++
    }

    /** Removes the flight and every log file recorded for it, on disk and in the index. */
    fun deleteFlight(session: FlightSessionEntity) {
        viewModelScope.launch {
            withContext(io) {
                files.listForSession(session.id).forEach { file ->
                    File(file.path).delete()
                    files.delete(file.id)
                }
                if (session.tlogPath.isNotEmpty()) File(session.tlogPath).delete()
                sessions.delete(session)
            }
        }
    }

    fun deleteLogFile(file: TlogFileEntity) {
        viewModelScope.launch {
            withContext(io) {
                File(file.path).delete()
                files.delete(file.id)
            }
        }
    }
}
