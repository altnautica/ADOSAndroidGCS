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
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.feature.configure.R
import com.altnautica.gcs.feature.configure.common.fcVariant
import com.altnautica.gcs.feature.configure.setup.SetupCatalog
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** ArduPilot's airspeed sensor type; 0 means none is fitted. */
private const val ARSPD_TYPE = "ARSPD_TYPE"

/** Why calibration cannot start; null when it may. */
enum class CalibrationBlock { NoVehicle, LinkStale, Armed }

/** Accelerometer step: [index] is 1-based of [total] poses. */
data class AccelStep(val pose: AccelPose, val index: Int, val total: Int = AccelPose.entries.size)

/** Words the operator must act on before [this] calibration starts; null when none. */
fun CalibrationKind.precaution(): Int? = when (this) {
    CalibrationKind.Esc -> R.string.cfg_cal_esc_precaution
    CalibrationKind.Airspeed -> R.string.cfg_cal_airspeed_precaution
    else -> null
}

/** The vehicle's setup step that runs [this] calibration when the vehicle is agent-connected; null when none does. */
fun CalibrationKind.agentStep(): String? = when (this) {
    CalibrationKind.Accel -> "accel"
    CalibrationKind.Level -> "level"
    CalibrationKind.Compass -> "compass"
    CalibrationKind.Radio -> "radio"
    // The vehicle's engine calibrates the gyros as part of the accelerometer step.
    CalibrationKind.Gyro -> null
    // Run straight on the flight controller only.
    CalibrationKind.Esc, CalibrationKind.Airspeed -> null
}

/**
 * ArduPilot applies the offsets of [this] calibration only after a reboot; PX4 applies its
 * compass, accelerometer and level offsets at once.
 */
fun CalibrationKind.needsReboot(variant: FcVariant): Boolean = variant == FcVariant.MavlinkArdupilot &&
    (this == CalibrationKind.Compass || this == CalibrationKind.Accel || this == CalibrationKind.Level)

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
    val variant: FcVariant = FcVariant.Unknown,
    /** MAV_TYPE from the heartbeat; null while unknown. */
    val mavType: Int? = null,
    /** The loaded parameters configure an airspeed sensor (`ARSPD_TYPE` present and not 0). */
    val airspeedSensor: Boolean = false,
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

    /**
     * Whether [kind] is offered on this link and vehicle: ESC on PX4 rotorcraft (ArduPilot
     * calibrates ESCs at boot through a parameter), airspeed on fixed wing (on ArduPilot only
     * with a configured sensor: without one its airspeed slot only zeroes the barometer).
     */
    fun offers(kind: CalibrationKind): Boolean {
        if (agent) return kind.agentStep() != null
        val copter = mavType in SetupCatalog.COPTER_TYPES
        return when (kind) {
            CalibrationKind.Esc -> copter && variant == FcVariant.MavlinkPx4
            CalibrationKind.Airspeed -> mavType in SetupCatalog.PLANE_TYPES &&
                (variant != FcVariant.MavlinkArdupilot || airspeedSensor)
            else -> true
        }
    }
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
            t?.let {
                combine(it.vehicle.telemetry.heartbeat, it.calibration.state, it.params.snapshot) { hb, cal, params ->
                    Triple(hb, cal, params.values[ARSPD_TYPE]?.let { p -> p.value != 0.0 } == true)
                }
            } ?: flowOf(Triple(null, CalibrationState.Idle, false))
        },
        target,
        busy,
        lastResult,
        ticks,
    ) { (hb, cal, airspeedSensor), t, isBusy, result, _ ->
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
            variant = hb?.fcVariant() ?: FcVariant.Unknown,
            mavType = hb?.vehicleType,
            airspeedSensor = airspeedSensor,
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
                busy = base.busy || snap?.actionsBlocked == true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CalibrationUiState())

    private val _rebootAdvised = MutableStateFlow(false)

    /**
     * An ArduPilot calibration whose offsets apply after a reboot (compass, accelerometer,
     * level) just finished; the host offers the reboot, then calls
     * [consumeRebootAdvice].
     */
    val rebootAdvised: StateFlow<Boolean> = _rebootAdvised.asStateFlow()

    init {
        viewModelScope.launch {
            var previous: CalibrationState = CalibrationState.Idle
            state.collect { s ->
                val cal = s.calibration
                // Only a finish seen here: a success left over from an earlier visit does not count.
                val wasRunning = previous !is CalibrationState.Idle && previous !is CalibrationState.Succeeded &&
                    previous !is CalibrationState.Failed
                if (!s.agent && wasRunning && cal is CalibrationState.Succeeded && cal.kind.needsReboot(s.variant)) _rebootAdvised.value = true
                previous = cal
            }
        }
    }

    fun consumeRebootAdvice() {
        _rebootAdvised.value = false
    }

    fun start(kind: CalibrationKind) {
        val current = state.value
        if (!current.canStart || !current.offers(kind)) return
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
                CalibrationKind.Esc -> t.calibration.startEsc()
                CalibrationKind.Airspeed -> t.calibration.startAirspeed()
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
