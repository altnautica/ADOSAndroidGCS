package com.altnautica.gcs.feature.configure.motor

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.model.VehicleProvider
import com.altnautica.gcs.feature.configure.common.secondTicks
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why motor buttons are disabled; null when a test may run. */
enum class MotorTestBlock { NoVehicle, LinkStale, Armed, NotAcknowledged, Running }

data class MotorTestUiState(
    val acknowledged: Boolean = false,
    val throttlePct: Float = MotorTestViewModel.DEFAULT_THROTTLE_PCT,
    val durationS: Float = MotorTestViewModel.DEFAULT_DURATION_S,
    val motorCount: Int = 4,
    val runningMotor: Int? = null,
    val lastResult: CommandResult? = null,
    val block: MotorTestBlock? = MotorTestBlock.NoVehicle,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = MotorTestViewModel.Factory::class)
class MotorTestViewModel internal constructor(
    nodeId: DeviceId,
    vehicles: VehicleProvider,
    private val now: () -> Long,
    ticks: Flow<Unit>,
) : ViewModel() {
    @AssistedInject constructor(@Assisted nodeId: String, vehicles: VehicleProvider) :
        this(DeviceId(nodeId), vehicles, SystemClock::elapsedRealtime, secondTicks())

    @AssistedFactory
    interface Factory {
        // A String id: KSP cannot name a factory method that takes an inline value class.
        fun create(nodeId: String): MotorTestViewModel
    }

    private val form = MutableStateFlow(MotorTestUiState())
    private val vehicle = vehicles.vehicle(nodeId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val ticker = ticks

    val state: StateFlow<MotorTestUiState> = combine(
        form,
        vehicle,
        vehicle.flatMapLatest { it?.telemetry?.heartbeat ?: flowOf(null) },
        ticker,
    ) { f, v, hb, _ ->
        val block = when {
            v == null || hb == null -> MotorTestBlock.NoVehicle
            now() - hb.tMs > LINK_FRESH_MS -> MotorTestBlock.LinkStale
            hb.armed -> MotorTestBlock.Armed
            !f.acknowledged -> MotorTestBlock.NotAcknowledged
            f.runningMotor != null -> MotorTestBlock.Running
            else -> null
        }
        f.copy(block = block)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MotorTestUiState())

    /** The operator confirms the propellers are removed. Required before any motor spins. */
    fun setAcknowledged(value: Boolean) = form.update { it.copy(acknowledged = value) }

    fun setThrottle(pct: Float) = form.update { it.copy(throttlePct = pct.coerceIn(0f, MAX_THROTTLE_PCT)) }

    fun setDuration(seconds: Float) = form.update { it.copy(durationS = seconds.coerceIn(0.5f, MAX_DURATION_S)) }

    fun setMotorCount(count: Int) = form.update { it.copy(motorCount = count.coerceIn(1, MAX_MOTORS)) }

    /** Spins one motor (1-based, in the flight controller's test order). Ignored while blocked. */
    fun test(motor: Int) {
        val current = state.value
        val v: Vehicle = vehicle.value ?: return
        if (current.block != null || motor !in 1..current.motorCount) return
        form.update { it.copy(runningMotor = motor, lastResult = null) }
        viewModelScope.launch {
            val result = v.control.run(VehicleCommand.MotorTest(motor, current.throttlePct, current.durationS))
            form.update { it.copy(runningMotor = null, lastResult = result) }
        }
    }

    companion object {
        const val DEFAULT_THROTTLE_PCT = 5f
        const val MAX_THROTTLE_PCT = 20f
        const val DEFAULT_DURATION_S = 2f
        const val MAX_DURATION_S = 10f
        const val MAX_MOTORS = 12
        const val LINK_FRESH_MS = 3_000L
    }
}
