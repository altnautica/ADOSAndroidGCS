package com.altnautica.gcs.ui.flightlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.data.flightlog.FlightSession
import com.altnautica.gcs.data.flightlog.FlightSessionDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FlightHistoryViewModel @Inject constructor(
    private val flightSessionDao: FlightSessionDao,
) : ViewModel() {

    val sessions: StateFlow<List<FlightSession>> = flightSessionDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteSession(session: FlightSession) {
        viewModelScope.launch {
            flightSessionDao.delete(session)
        }
    }
}
