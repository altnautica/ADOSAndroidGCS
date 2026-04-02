package com.altnautica.gcs.ui.flightlog

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.data.flightlog.FlightSession
import com.altnautica.gcs.data.flightlog.FlightSessionDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FlightDetailViewModel @Inject constructor(
    private val flightSessionDao: FlightSessionDao,
) : ViewModel() {

    private val _session = mutableStateOf<FlightSession?>(null)
    val session: State<FlightSession?> = _session

    private var saveNotesJob: Job? = null

    fun loadSession(id: Long) {
        viewModelScope.launch {
            _session.value = flightSessionDao.getById(id)
        }
    }

    /**
     * Debounced save of notes. Waits 500ms after last keystroke before writing to DB.
     */
    fun updateNotes(sessionId: Long, notes: String) {
        saveNotesJob?.cancel()
        saveNotesJob = viewModelScope.launch {
            delay(500)
            val current = flightSessionDao.getById(sessionId) ?: return@launch
            flightSessionDao.update(current.copy(notes = notes))
        }
    }
}
