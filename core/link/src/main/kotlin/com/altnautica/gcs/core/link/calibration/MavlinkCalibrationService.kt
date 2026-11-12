package com.altnautica.gcs.core.link.calibration

import com.altnautica.gcs.core.link.command.CommandService
import com.altnautica.gcs.core.link.mavlink.MavlinkLink
import com.altnautica.gcs.core.link.mavlink.targetMessages
import com.altnautica.gcs.core.link.mavlink.VehicleTarget
import com.altnautica.gcs.core.link.param.ParamService
import com.altnautica.gcs.core.link.param.ParamSetResult
import com.altnautica.gcs.core.link.param.ParamValue
import com.altnautica.gcs.core.mavlink.enums.AccelcalVehiclePos
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.enums.MavType
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
 * - compass: ArduPilot runs `MAV_CMD_DO_START_MAG_CAL`, `MAG_CAL_PROGRESS`/`MAG_CAL_REPORT`,
 *   then accept or cancel; PX4 runs p2=1 and reports through `[cal]` status texts;
 * - ESC (PX4 only, p7=1) and airspeed (ArduPilot p3=1, PX4 p6=2) follow the gyro pattern:
 *   the final ACK on ArduPilot, the `[cal]` texts on PX4;
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
            // ArduPilot's final ACK (after any IN_PROGRESS) comes once the calibration is done.
            else -> succeed(kind, lastText)
        }
        return r
    }

    private fun isPx4(): Boolean = link.target.value?.family == FirmwareFamily.Px4

    // p7=1 is PX4's ESC slot; ArduPilot has no handler for it and calibrates ESCs at boot (ESC_CALIBRATION).
    override suspend fun startEsc(): CommandResult {
        if (!isPx4()) return CommandResult.Unsupported
        return simple(CalibrationKind.Esc, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
    }

    // ArduPilot zeroes the airspeed sensor with the barometer (p3=1: IN_PROGRESS, then the result);
    // PX4 has its own airspeed slot (p6=2).
    override suspend fun startAirspeed(): CommandResult =
        if (isPx4()) simple(CalibrationKind.Airspeed, 0f, 0f, 0f, 0f, 0f, 2f) else simple(CalibrationKind.Airspeed, 0f, 0f, 1f)

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
        // PX4 has no onboard MAG_CAL protocol: its compass calibration is p2=1 with `[cal]` texts.
        if (isPx4()) return simple(CalibrationKind.Compass, 0f, 1f)
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
        val all = radio.values.toList()
        if (all.isEmpty()) return CommandResult.Rejected(-1, "noRcInput")
        val sticks = radioSticks(link.target.value, params.snapshot.value.values)
            ?: return CommandResult.Rejected(-1, "rcMapNotLoaded")
        // Keep the screen in the Radio state: the operator moves the sticks further and saves again.
        val channels = radioChannelsToWrite(all, sticks) ?: return CommandResult.Rejected(-1, "sticksNotMoved")
        for (c in channels) {
            val trim = if (c.channel == sticks.throttle) c.min else c.current.coerceIn(c.min, c.max)
            for ((suffix, v) in listOf("MIN" to c.min, "MAX" to c.max, "TRIM" to trim)) {
                when (val r = params.set("RC${c.channel}_$suffix", v.toDouble())) {
                    is ParamSetResult.Confirmed -> Unit
                    else -> {
                        val reason = paramWriteReason(r)
                        fail(CalibrationKind.Radio, reason)
                        return CommandResult.Rejected(-1, reason)
                    }
                }
            }
        }
        stopWatching()
        _state.value = CalibrationState.Succeeded(CalibrationKind.Radio, null)
        return CommandResult.Accepted
    }

    override suspend fun cancel() {
        when (val current = _state.value) {
            is CalibrationState.Compass -> commands.long(MavCmd.MAV_CMD_DO_CANCEL_MAG_CAL, 0f)
            // PX4 cancels a running sensor calibration on PREFLIGHT_CALIBRATION with every parameter 0.
            is CalibrationState.Running -> if (current.kind == CalibrationKind.Compass && isPx4()) {
                commands.long(MavCmd.MAV_CMD_PREFLIGHT_CALIBRATION, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            }
            else -> Unit
        }
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
        else -> "failed"
    }

    internal companion object {
        private const val UINT16_MAX = 0xFFFF

        /** A channel that moved less than this (µs) was not touched by the operator and is left alone. */
        const val MIN_MOVED_US = 50

        /** Every stick channel must span at least this (µs) before a radio calibration is saved. */
        const val MIN_STICK_SPAN_US = 200

        /**
         * The sticks of the vehicle behind [target], from its map parameters (`RC_MAP_*` on PX4,
         * where 0 is an unmapped axis; `RCMAP_*` on ArduPilot). Rovers and boats check steering
         * and throttle only: ArduPilot steers on the roll channel, and PX4's stick mapping
         * picks two of its four axes. Null while any map parameter is not loaded.
         */
        fun radioSticks(target: VehicleTarget?, values: Map<String, ParamValue>): RadioSticks? {
            val px4 = target?.family == FirmwareFamily.Px4
            val surface = target?.vehicleType == MavType.MAV_TYPE_GROUND_ROVER || target?.vehicleType == MavType.MAV_TYPE_SURFACE_BOAT
            val prefix = if (px4) "RC_MAP_" else "RCMAP_"
            val axes = if (surface && !px4) listOf("ROLL", "THROTTLE") else listOf("ROLL", "PITCH", "THROTTLE", "YAW")
            val mapped = axes.associateWith { values["$prefix$it"]?.value?.toInt() ?: return null }.filterValues { it > 0 }
            val channels = mapped.values.distinct()
            return RadioSticks(
                channels = channels,
                required = if (surface && px4) minOf(2, channels.size) else channels.size,
                throttle = if (surface) null else mapped["THROTTLE"],
            )
        }

        /**
         * The channels a radio calibration writes: only those the operator moved. Null when fewer
         * than [RadioSticks.required] stick channels span [MIN_STICK_SPAN_US], so nothing is written.
         */
        fun radioChannelsToWrite(channels: List<RcChannelRange>, sticks: RadioSticks): List<RcChannelRange>? {
            val byChannel = channels.associateBy { it.channel }
            val moved = sticks.channels.count { ch -> byChannel[ch]?.let { it.max - it.min >= MIN_STICK_SPAN_US } == true }
            if (moved < sticks.required) return null
            return channels.filter { it.max - it.min >= MIN_MOVED_US }
        }

        /** A reason token (see `linkReasonRes`) for a radio-calibration parameter write that did not confirm. */
        fun paramWriteReason(r: ParamSetResult): String = when (r) {
            ParamSetResult.Timeout -> "timeout"
            is ParamSetResult.Failed -> r.reason
            else -> "writeFailed"
        }
    }
}

/**
 * The stick channels a radio calibration checks: at least [required] of [channels] must each
 * span [MavlinkCalibrationService.MIN_STICK_SPAN_US]. [throttle] trims to its minimum; null when
 * the throttle rests centred (rover and boat: forward and reverse).
 */
internal data class RadioSticks(val channels: List<Int>, val required: Int = channels.size, val throttle: Int? = null)
