package com.altnautica.gcs.feature.configure.calibration

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.fcsetup.FcSetupApi
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.link.calibration.AccelPose
import com.altnautica.gcs.core.link.calibration.CalibrationKind
import com.altnautica.gcs.core.link.calibration.CalibrationState
import com.altnautica.gcs.core.link.calibration.CompassStatus
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.configure.common.ConfigureTarget
import com.altnautica.gcs.feature.configure.common.ConfigureTargetSource
import com.altnautica.gcs.feature.configure.common.LINK_FRESH_MS
import com.altnautica.gcs.feature.configure.common.secondTicks
import com.altnautica.gcs.feature.configure.wizard.SetupBackendSource
import com.altnautica.gcs.feature.configure.wizard.SetupProblem
import com.altnautica.gcs.feature.configure.wizard.SetupSession
import com.altnautica.gcs.feature.configure.wizard.SetupSnapshotState
import com.altnautica.gcs.feature.configure.wizard.SetupText
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Why calibration cannot start; null when it may. */
enum class CalibrationBlock { NoVehicle, LinkStale, Armed }

/** Accelerometer step: [index] is 1-based of [total] poses. */
data class AccelStep(val pose: AccelPose, val index: Int, val total: Int = AccelPose.entries.size)

/** The vehicle's setup step that runs [this] calibration when the vehicle is agent-connected; null when none does. */
fun CalibrationKind.agentStep(): String? = when (this) {
    CalibrationKind.Accel -> "accel"
    CalibrationKind.Level -> "level"
    CalibrationKind.Compass -> "compass"
    CalibrationKind.Radio -> "radio"
    // The vehicle's engine calibrates the gyros as part of the accelerometer step.
    CalibrationKind.Gyro -> null
}

data class CalibrationUiState(
    val block: CalibrationBlock? = CalibrationBlock.NoVehicle,
    val calibration: CalibrationState = CalibrationState.Idle,
    val accelStep: AccelStep? = null,
    /** Mean completion over every compass being calibrated, 0-100. */
    val compassPct: Int? = null,
    val canAcceptCompass: Boolean = false,
    val busy: Boolean = false,
    val lastResult: CommandResult? = null,
    /** The vehicle's computer runs calibrations: each one is a single setup step on the agent. */
    val agent: Boolean = false,
    /** The agent step started from this screen, live. */
    val agentStep: SetupStep? = null,
    val agentProblem: SetupProblem? = null,
) {
    val running: Boolean
        get() = if (agent) {
            agentStep?.state == com.altnautica.gcs.core.agent.fcsetup.SetupStepState.Running ||
                agentStep?.state == com.altnautica.gcs.core.agent.fcsetup.SetupStepState.WaitingUser
        } else {
            calibration !is CalibrationState.Idle &&
                calibration !is CalibrationState.Succeeded && calibration !is CalibrationState.Failed
        }

    /** A new calibration may start: link usable, disarmed, nothing running. */
    val canStart: Boolean get() = block == null && !running && !busy

    /** Whether [kind] is offered on this link. */
    fun offers(kind: CalibrationKind): Boolean = !agent || kind.agentStep() != null
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = CalibrationViewModel.Factory::class)
class CalibrationViewModel internal constructor(
    nodeId: DeviceId,
    targets: ConfigureTargetSource,
    private val now: () -> Long,
    ticks: Flow<Unit>,
    backends: SetupBackendSource = SetupBackendSource { flowOf(null) },
    /** Setup strings for agent steps. */
    val text: SetupText = SetupText { key, _ -> key },
) : ViewModel() {
    @AssistedInject constructor(
        @Assisted nodeId: String,
        targets: ConfigureTargetSource,
        backends: SetupBackendSource,
        text: SetupText,
    ) : this(DeviceId(nodeId), targets, SystemClock::elapsedRealtime, secondTicks(), backends, text)

    @AssistedFactory
    interface Factory {
        // A String id: KSP cannot name a factory method that takes an inline value class.
        fun create(nodeId: String): CalibrationViewModel
    }

    private val target = targets.observe(nodeId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val busy = MutableStateFlow(false)
    private val lastResult = MutableStateFlow<CommandResult?>(null)
    private val agentStepId = MutableStateFlow<String?>(null)

    private var session: SetupSession? = null
    private val sessions: StateFlow<SetupSession?> = backends.observe(nodeId).map { backend ->
        session?.close()
        session = backend?.let { SetupSession(it, viewModelScope).also(SetupSession::open) }
        session
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val agentSnapshot: Flow<Pair<Boolean, SetupSnapshotState?>> =
        sessions.flatMapLatest { s -> s?.state?.map { true to it } ?: flowOf(false to null) }

    private val mavlinkState = combine(
        target.flatMapLatest { t ->
            t?.let { combine(it.vehicle.telemetry.heartbeat, it.calibration.state) { hb, cal -> hb to cal } }
                ?: flowOf(null to CalibrationState.Idle)
        },
        target,
        busy,
        lastResult,
        ticks,
    ) { (hb, cal), t, isBusy, result, _ ->
        val block = when {
            t == null || hb == null -> CalibrationBlock.NoVehicle
            now() - hb.tMs > LINK_FRESH_MS -> CalibrationBlock.LinkStale
            hb.armed -> CalibrationBlock.Armed
            else -> null
        }
        val compass = cal as? CalibrationState.Compass
        CalibrationUiState(
            block = block,
            calibration = cal,
            accelStep = (cal as? CalibrationState.AccelPosition)?.let { AccelStep(it.pose, it.pose.ordinal + 1) },
            compassPct = compass?.progress?.takeIf { it.isNotEmpty() }?.let { p -> p.sumOf { it.completionPct } / p.size },
            canAcceptCompass = compass != null && compass.readyToAccept && !isBusy &&
                compass.reports.isNotEmpty() && compass.reports.all { it.status == CompassStatus.Success },
            busy = isBusy,
            lastResult = result,
        )
    }

    val state: StateFlow<CalibrationUiState> = combine(mavlinkState, agentSnapshot, agentStepId) { base, (agent, snap), stepId ->
        if (!agent) {
            base
        } else {
            base.copy(
                agent = true,
                agentStep = stepId?.let { snap?.step(FcSetupApi.WIZARD_GROUND, it) },
                agentProblem = snap?.problem,
                busy = base.busy || snap?.busy == true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CalibrationUiState())

    fun start(kind: CalibrationKind) {
        val current = state.value
        if (!current.canStart) return
        if (current.agent) {
            val step = kind.agentStep() ?: return
            agentStepId.value = step
            session?.runStep(FcSetupApi.WIZARD_GROUND, step)
            return
        }
        run { t ->
            when (kind) {
                CalibrationKind.Gyro -> t.calibration.startGyro()
                CalibrationKind.Level -> t.calibration.startLevel()
                CalibrationKind.Accel -> t.calibration.startAccel()
                CalibrationKind.Compass -> t.calibration.startCompass()
                CalibrationKind.Radio -> {
                    t.calibration.startRadio()
                    null
                }
            }
        }
    }

    /** An action of the running agent step (next pose, accept, cancel, ...). */
    fun agentAction(action: String, args: JsonObject?) {
        val step = state.value.agentStep ?: return
        session?.action(step.wizard, step.step, action, args)
    }

    /** The vehicle sits in the requested pose. */
    fun confirmAccelPosition() {
        if (state.value.accelStep == null || state.value.busy) return
        run { it.calibration.confirmAccelPosition() }
    }

    fun acceptCompass() {
        if (!state.value.canAcceptCompass) return
        run { it.calibration.acceptCompass() }
    }

    fun saveRadio() {
        if (state.value.calibration !is CalibrationState.Radio || state.value.busy) return
        run { it.calibration.saveRadio() }
    }

    fun cancel() {
        if (state.value.agent) {
            session?.abort(FcSetupApi.WIZARD_GROUND)
            return
        }
        val t = target.value ?: return
        viewModelScope.launch {
            if (state.value.calibration is CalibrationState.Compass) t.calibration.cancelCompass()
            t.calibration.cancel()
        }
    }

    private fun run(action: suspend (ConfigureTarget) -> CommandResult?) {
        val t = target.value ?: return
        busy.value = true
        lastResult.value = null
        viewModelScope.launch {
            try {
                lastResult.value = action(t)
            } finally {
                busy.value = false
            }
        }
    }

    override fun onCleared() {
        session?.close()
        super.onCleared()
    }
}
