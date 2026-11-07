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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.altnautica.gcs.core.agent.fcsetup.FcSetupApi
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.feature.configure.wizard.SetupProblem
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why motor buttons are disabled; null when a test may run. */
enum class MotorTestBlock { NoVehicle, LinkStale, Armed, NotAcknowledged, Running }

data class MotorTestUiState(
    val acknowledged: Boolean = false,
    val throttlePct: Float = MotorTestViewModel.DEFAULT_THROTTLE_PCT,
    val motorCount: Int = 4,
    val runningMotor: Int? = null,
    val lastResult: CommandResult? = null,
    val block: MotorTestBlock? = MotorTestBlock.NoVehicle,
)

/** The vehicle's `outputs` step as the agent reports it; [step] is null until it has run. */
data class AgentMotorTest(val step: SetupStep?, val busy: Boolean, val problem: SetupProblem?)

/** The setup step that tests the motors and control surfaces. */
const val OUTPUTS_STEP = "outputs"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = MotorTestViewModel.Factory::class)
class MotorTestViewModel internal constructor(
    nodeId: DeviceId,
    vehicles: VehicleProvider,
    private val now: () -> Long,
    ticks: Flow<Unit>,
    backends: com.altnautica.gcs.feature.configure.wizard.SetupBackendSource =
        com.altnautica.gcs.feature.configure.wizard.SetupBackendSource { flowOf(null) },
    /** Setup strings for the agent motor test step. */
    val text: com.altnautica.gcs.feature.configure.wizard.SetupText =
        com.altnautica.gcs.feature.configure.wizard.SetupText { key, _ -> key },
) : ViewModel() {
    @AssistedInject constructor(
        @Assisted nodeId: String,
        vehicles: VehicleProvider,
        backends: com.altnautica.gcs.feature.configure.wizard.SetupBackendSource,
        text: com.altnautica.gcs.feature.configure.wizard.SetupText,
    ) : this(DeviceId(nodeId), vehicles, SystemClock::elapsedRealtime, secondTicks(), backends, text)

    @AssistedFactory
    interface Factory {
        // A String id: KSP cannot name a factory method that takes an inline value class.
        fun create(nodeId: String): MotorTestViewModel
    }

    private val form = MutableStateFlow(MotorTestUiState())
    private val vehicle = vehicles.vehicle(nodeId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val ticker = ticks

    private var session: com.altnautica.gcs.feature.configure.wizard.SetupSession? = null

    /**
     * When the vehicle is agent-connected the motor test is the vehicle's `outputs` setup
     * step: the vehicle spins each motor behind its own propellers-off hold-confirm.
     */
    val agent: StateFlow<AgentMotorTest?> = backends.observe(nodeId).map { backend ->
        session?.close()
        session = backend?.let { com.altnautica.gcs.feature.configure.wizard.SetupSession(it, viewModelScope).also { s -> s.open() } }
        session
    }.flatMapLatest { s ->
        s?.state?.map { snap ->
            AgentMotorTest(snap.step(FcSetupApi.WIZARD_GROUND, OUTPUTS_STEP), snap.busy, snap.problem)
        } ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Runs the vehicle's motor test step on its own. */
    fun runAgentTest() {
        session?.runStep(FcSetupApi.WIZARD_GROUND, OUTPUTS_STEP)
    }

    fun agentAction(action: String, args: kotlinx.serialization.json.JsonObject?) {
        session?.action(FcSetupApi.WIZARD_GROUND, OUTPUTS_STEP, action, args)
    }

    fun abortAgentTest() {
        session?.abort(FcSetupApi.WIZARD_GROUND)
    }
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

    fun setMotorCount(count: Int) = form.update { it.copy(motorCount = count.coerceIn(1, MAX_MOTORS)) }

    private var holdJob: Job? = null

    /**
     * Spins one motor (1-based, in the flight controller's test order) while the button is
     * held: the test command goes out at once and again every [HOLD_RESEND_MS] with a 1 s
     * duration, so the motor stops within a second of [endHold]. Ignored while blocked.
     */
    fun startHold(motor: Int) {
        val current = state.value
        val v: Vehicle = vehicle.value ?: return
        if (current.block != null || motor !in 1..current.motorCount) return
        form.update { it.copy(runningMotor = motor, lastResult = null) }
        holdJob = viewModelScope.launch {
            var inFlight: Job? = null
            while (true) {
                val s = state.value
                // Arming, a stale link or a withdrawn acknowledgement ends the hold.
                if (form.value.runningMotor != motor || (s.block != null && s.block != MotorTestBlock.Running)) break
                // One exchange at a time: a send still waiting for its ACK is not stacked behind.
                if (inFlight?.isActive != true) {
                    inFlight = launch {
                        val result = v.control.run(VehicleCommand.MotorTest(motor, s.throttlePct, HOLD_COMMAND_DURATION_S))
                        form.update { it.copy(lastResult = result) }
                        if (result != CommandResult.Accepted) form.update { it.copy(runningMotor = null) }
                    }
                }
                delay(HOLD_RESEND_MS)
            }
            inFlight?.cancel()
            form.update { if (it.runningMotor == motor) it.copy(runningMotor = null) else it }
        }
    }

    /** The operator released the motor button: no further test commands go out. */
    fun endHold() {
        holdJob?.cancel()
        holdJob = null
        form.update { it.copy(runningMotor = null) }
    }

    /** Leaving the motor test withdraws the propellers-off acknowledgement. */
    fun resetAcknowledgement() {
        endHold()
        form.update { it.copy(acknowledged = false) }
    }

    override fun onCleared() {
        resetAcknowledgement()
        session?.close()
        super.onCleared()
    }

    companion object {
        const val DEFAULT_THROTTLE_PCT = 5f
        const val MAX_THROTTLE_PCT = 20f
        const val MAX_MOTORS = 12
        const val LINK_FRESH_MS = 3_000L
        const val HOLD_RESEND_MS = 800L
        const val HOLD_COMMAND_DURATION_S = 1.0f
    }
}
