package com.altnautica.gcs.core.link.calibration

import com.altnautica.gcs.core.link.command.CommandService
import com.altnautica.gcs.core.link.mavlink.MavlinkLink
import com.altnautica.gcs.core.link.mavlink.targetMessages
import com.altnautica.gcs.core.link.param.ParamService
import com.altnautica.gcs.core.link.param.ParamSetResult
import com.altnautica.gcs.core.mavlink.enums.AccelcalVehiclePos
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.messages.CommandLong
import com.altnautica.gcs.core.mavlink.messages.MagCalProgress
import com.altnautica.gcs.core.mavlink.messages.MagCalReport
import com.altnautica.gcs.core.mavlink.messages.RcChannels
import com.altnautica.gcs.core.mavlink.messages.Statustext
import com.altnautica.gcs.core.mavlink.modes.FirmwareFamily
import com.altnautica.gcs.core.model.CommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Calibration over MAVLink, following ArduPilot's protocols:
 *
 * - gyro / level: `MAV_CMD_PREFLIGHT_CALIBRATION` (p1=1 / p5=2); ArduPilot's
 *   result is the ACK, PX4's the `[cal]` status text that follows it;
 * - accelerometer: p5=1, then the autopilot drives the six poses with
 *   `COMMAND_LONG(MAV_CMD_ACCELCAL_VEHICLE_POS)` sent to the GCS; the operator's
 *   confirmation is echoed back with the same command;
 * - compass: `MAV_CMD_DO_START_MAG_CAL`, `MAG_CAL_PROGRESS`/`MAG_CAL_REPORT`,
 *   then accept or cancel;
 * - radio: `RC_CHANNELS` min/max capture written to `RCn_MIN/MAX/TRIM`.
 *
 * PX4 reports its own calibration steps through `[cal]` status texts, which
 * drive [CalibrationState.Running] messages and the final result.
 */
class MavlinkCalibrationService(
    private val scope: CoroutineScope,
    private val link: MavlinkLink,
    private val commands: CommandService,
    private val params: ParamService,
) : CalibrationService {

    private val _state = MutableStateFlow<CalibrationState>(CalibrationState.Idle)
    override val state: StateFlow<CalibrationState> = _state.asStateFlow()

    private var watcher: Job? = null
    private val compassProgress = sortedMapOf<Int, CompassProgress>()
    private val compassReports = sortedMapOf<Int, CompassReport>()
    private var compassMask = 0
    private val radio = sortedMapOf<Int, RcChannelRange>()

    override suspend fun startGyro(): CommandResult = simple(CalibrationKind.Gyro, 1f, 0f, 0f, 0f, 0f)

    override suspend fun startLevel(): CommandResult = simple(CalibrationKind.Level, 0f, 0f, 0f, 0f, 2f)

    private suspend fun simple(kind: CalibrationKind, vararg p: Float): CommandResult {
        begin(kind)
        val r = commands.long(MavCmd.MAV_CMD_PREFLIGHT_CALIBRATION, *p)
        // A status text may already have settled the result while the ACK was awaited.
        if (_state.value !is CalibrationState.Running) return r
        when {
            r != CommandResult.Accepted -> fail(kind, r.reason())
            // PX4 ACKs the start and runs the calibration afterwards; its `[cal]` texts give the result.
            isPx4() -> Unit
            // ArduPilot ACKs gyro and level calibration once they are done.
            else -> succeed(kind, lastText)
        }
        return r
    }

    private fun isPx4(): Boolean = link.target.value?.family == FirmwareFamily.Px4

    override suspend fun startAccel(): CommandResult {
        begin(CalibrationKind.Accel)
        val r = commands.long(MavCmd.MAV_CMD_PREFLIGHT_CALIBRATION, 0f, 0f, 0f, 0f, 1f)
        if (r != CommandResult.Accepted) fail(CalibrationKind.Accel, r.reason())
        return r
    }

    override suspend fun confirmAccelPosition(): CommandResult {
        val pose = (_state.value as? CalibrationState.AccelPosition)?.pose ?: return CommandResult.Rejected(-1, "notWaiting")
        _state.value = CalibrationState.Running(CalibrationKind.Accel, lastText)
        return commands.long(MavCmd.MAV_CMD_ACCELCAL_VEHICLE_POS, pose.wire.toFloat())
    }

    override suspend fun startCompass(): CommandResult {
        compassProgress.clear()
        compassReports.clear()
        compassMask = 0
        begin(CalibrationKind.Compass)
        _state.value = CalibrationState.Compass(emptyList(), emptyList(), readyToAccept = false)
        // all compasses, no retry, no autosave (operator accepts), no delay, no reboot
        val r = commands.long(MavCmd.MAV_CMD_DO_START_MAG_CAL, 0f, 0f, 0f, 0f, 0f)
        if (r != CommandResult.Accepted) fail(CalibrationKind.Compass, r.reason())
        return r
    }

    override suspend fun acceptCompass(): CommandResult {
        val r = commands.long(MavCmd.MAV_CMD_DO_ACCEPT_MAG_CAL, 0f)
        _state.value = if (r == CommandResult.Accepted) {
            CalibrationState.Succeeded(CalibrationKind.Compass, null)
        } else {
            CalibrationState.Failed(CalibrationKind.Compass, r.reason())
        }
        stopWatching()
        return r
    }

    override suspend fun cancelCompass(): CommandResult {
        val r = commands.long(MavCmd.MAV_CMD_DO_CANCEL_MAG_CAL, 0f)
        stopWatching()
        _state.value = CalibrationState.Idle
        return r
    }

    override fun startRadio() {
        radio.clear()
        begin(CalibrationKind.Radio)
        _state.value = CalibrationState.Radio(emptyList())
    }

    override suspend fun saveRadio(): CommandResult {
        val channels = radio.values.toList()
        if (channels.isEmpty()) return CommandResult.Rejected(-1, "noRcInput")
        val throttle = params.snapshot.value.values["RCMAP_THROTTLE"]?.value?.toInt() ?: DEFAULT_THROTTLE_CHANNEL
        for (c in channels) {
            val trim = if (c.channel == throttle) c.min else c.current.coerceIn(c.min, c.max)
            for ((suffix, v) in listOf("MIN" to c.min, "MAX" to c.max, "TRIM" to trim)) {
                when (val r = params.set("RC${c.channel}_$suffix", v.toDouble())) {
                    is ParamSetResult.Confirmed -> Unit
                    else -> {
                        fail(CalibrationKind.Radio, r.toString())
                        return CommandResult.Rejected(-1, "RC${c.channel}_$suffix")
                    }
                }
            }
        }
        stopWatching()
        _state.value = CalibrationState.Succeeded(CalibrationKind.Radio, null)
        return CommandResult.Accepted
    }

    override suspend fun cancel() {
        val current = _state.value
        if (current is CalibrationState.Compass) commands.long(MavCmd.MAV_CMD_DO_CANCEL_MAG_CAL, 0f)
        stopWatching()
        _state.value = CalibrationState.Idle
    }

    @Volatile
    private var lastText: String? = null

    private fun begin(kind: CalibrationKind) {
        stopWatching()
        lastText = null
        _state.value = CalibrationState.Running(kind, null)
        watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.targetMessages().collect { m -> onMessage(kind, m.message) }
        }
    }

    private fun stopWatching() {
        watcher?.cancel()
        watcher = null
    }

    private fun fail(kind: CalibrationKind, reason: String) {
        stopWatching()
        _state.value = CalibrationState.Failed(kind, reason)
    }

    private fun onMessage(kind: CalibrationKind, m: Any) {
        when (m) {
            is Statustext -> {
                lastText = m.text
                when (val s = _state.value) {
                    is CalibrationState.Running -> {
                        val lower = m.text.lowercase()
                        when {
                            "calibration done" in lower || "calibration successful" in lower ->
                                if (kind == CalibrationKind.Accel || isPx4()) succeed(kind, m.text)
                            "calibration failed" in lower || "calibration cancelled" in lower -> fail(kind, m.text)
                            else -> _state.value = s.copy(message = m.text)
                        }
                    }
                    is CalibrationState.AccelPosition -> _state.value = s.copy(message = m.text)
                    else -> Unit
                }
            }
            is CommandLong -> if (kind == CalibrationKind.Accel && m.command == MavCmd.MAV_CMD_ACCELCAL_VEHICLE_POS) {
                when (val pos = m.param1.toInt()) {
                    AccelcalVehiclePos.ACCELCAL_VEHICLE_POS_SUCCESS -> succeed(kind, lastText)
                    AccelcalVehiclePos.ACCELCAL_VEHICLE_POS_FAILED -> fail(kind, lastText ?: "failed")
                    else -> AccelPose.of(pos)?.let { _state.value = CalibrationState.AccelPosition(it, lastText) }
                }
            }
            is MagCalProgress -> if (kind == CalibrationKind.Compass) {
                compassMask = compassMask or m.calMask
                compassProgress[m.compassId] = CompassProgress(m.compassId, m.completionPct, compassStatus(m.calStatus))
                publishCompass()
            }
            is MagCalReport -> if (kind == CalibrationKind.Compass) {
                compassMask = compassMask or m.calMask
                compassReports[m.compassId] = CompassReport(m.compassId, compassStatus(m.calStatus), m.fitness, m.autosaved != 0)
                compassProgress[m.compassId] = CompassProgress(m.compassId, 100, compassStatus(m.calStatus))
                publishCompass()
            }
            is RcChannels -> if (kind == CalibrationKind.Radio) {
                val raw = intArrayOf(
                    m.chan1Raw, m.chan2Raw, m.chan3Raw, m.chan4Raw, m.chan5Raw, m.chan6Raw, m.chan7Raw, m.chan8Raw,
                    m.chan9Raw, m.chan10Raw, m.chan11Raw, m.chan12Raw, m.chan13Raw, m.chan14Raw, m.chan15Raw,
                    m.chan16Raw, m.chan17Raw, m.chan18Raw,
                )
                for (i in 0 until minOf(m.chancount, raw.size)) {
                    val v = raw[i]
                    if (v == 0 || v == UINT16_MAX) continue
                    val prev = radio[i + 1]
                    radio[i + 1] = if (prev == null) {
                        RcChannelRange(i + 1, v, v, v)
                    } else {
                        RcChannelRange(i + 1, v, minOf(prev.min, v), maxOf(prev.max, v))
                    }
                }
                _state.value = CalibrationState.Radio(radio.values.toList())
            }
        }
    }

    private fun succeed(kind: CalibrationKind, text: String?) {
        stopWatching()
        _state.value = CalibrationState.Succeeded(kind, text)
    }

    private fun publishCompass() {
        val expected = (0 until 8).filter { compassMask and (1 shl it) != 0 }
        val reports = compassReports.values.toList()
        val done = expected.isNotEmpty() && expected.all { compassReports.containsKey(it) }
        val allGood = done && reports.all { it.status == CompassStatus.Success }
        _state.value = CalibrationState.Compass(compassProgress.values.toList(), reports, readyToAccept = allGood)
    }

    private fun compassStatus(v: Int) = when (v) {
        0 -> CompassStatus.NotStarted
        1 -> CompassStatus.WaitingToStart
        2, 3 -> CompassStatus.Running
        4 -> CompassStatus.Success
        6 -> CompassStatus.BadOrientation
        7 -> CompassStatus.BadRadius
        else -> CompassStatus.Failed
    }

    private fun CommandResult.reason(): String = when (this) {
        is CommandResult.Rejected -> reason
        is CommandResult.Denied -> reason
        CommandResult.Timeout -> "timeout"
        CommandResult.Unsupported -> "unsupported"
        else -> toString()
    }

    private companion object {
        const val UINT16_MAX = 0xFFFF
        const val DEFAULT_THROTTLE_CHANNEL = 3
    }
}
