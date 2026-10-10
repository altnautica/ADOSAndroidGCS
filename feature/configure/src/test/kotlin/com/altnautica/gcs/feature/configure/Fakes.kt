package com.altnautica.gcs.feature.configure

import com.altnautica.gcs.core.link.calibration.CalibrationService
import com.altnautica.gcs.core.link.calibration.CalibrationState
import com.altnautica.gcs.core.link.param.ParamLoadResult
import com.altnautica.gcs.core.link.param.ParamService
import com.altnautica.gcs.core.link.param.ParamSetResult
import com.altnautica.gcs.core.link.param.ParamSnapshot
import com.altnautica.gcs.core.link.param.ParamValue
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleControl
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.feature.configure.common.ConfigureTarget
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

class FakeParamService(values: Map<String, Double> = emptyMap(), complete: Boolean = true) : ParamService {
    val writes = mutableListOf<Pair<String, Double>>()
    var refreshes = 0
    var nextSet: ((String, Double) -> ParamSetResult)? = null
    var refreshResult: ParamLoadResult = ParamLoadResult.Complete(values.size)

    override val snapshot = MutableStateFlow(
        if (complete) snapshotOf(values) else ParamSnapshot(values.size + 10, values.size, snapshotOf(values).values),
    )
    override val loading = MutableStateFlow(false)

    override suspend fun refresh(): ParamLoadResult {
        refreshes++
        return refreshResult
    }

    override suspend fun set(name: String, value: Double): ParamSetResult {
        writes += name to value
        nextSet?.let { return it(name, value) }
        val current = snapshot.value.values[name] ?: return ParamSetResult.UnknownParam
        val updated = current.copy(value = value)
        snapshot.value = snapshot.value.copy(values = snapshot.value.values + (name to updated))
        return ParamSetResult.Confirmed(updated)
    }

    override suspend fun read(name: String): ParamValue? = snapshot.value.values[name]

    companion object {
        fun snapshotOf(values: Map<String, Double>): ParamSnapshot {
            val map = values.entries.mapIndexed { i, (k, v) -> k to ParamValue(k, v, 9, i) }.toMap()
            return ParamSnapshot(map.size, map.size, map)
        }
    }
}

class FakeCalibrationService : CalibrationService {
    override val state = MutableStateFlow<CalibrationState>(CalibrationState.Idle)
    val calls = mutableListOf<String>()
    var result: CommandResult = CommandResult.Accepted

    private fun call(name: String): CommandResult {
        calls += name
        return result
    }

    override suspend fun startGyro() = call("gyro")
    override suspend fun startLevel() = call("level")
    override suspend fun startAccel() = call("accel")
    override suspend fun confirmAccelPosition() = call("confirmAccel")
    override suspend fun startCompass() = call("compass")
    override suspend fun acceptCompass() = call("acceptCompass")
    override suspend fun cancelCompass() = call("cancelCompass")
    override fun startRadio() {
        calls += "radio"
    }
    override suspend fun saveRadio() = call("saveRadio")
    override suspend fun cancel() {
        calls += "cancel"
        state.value = CalibrationState.Idle
    }
}

class FakeVehicle(autopilot: Int = 3, vehicleType: Int = 2, armed: Boolean = false, tMs: Long = 0) {
    val heartbeat = MutableStateFlow<HeartbeatState?>(
        HeartbeatState(armed = armed, mode = FlightMode(0, "Stabilize"), vehicleType = vehicleType, autopilot = autopilot, tMs = tMs),
    )
    val control = mockk<VehicleControl>(relaxed = true)
    private val telemetry = mockk<VehicleTelemetry> { every { heartbeat } returns this@FakeVehicle.heartbeat }
    val vehicle = mockk<Vehicle> {
        every { telemetry } returns this@FakeVehicle.telemetry
        every { control } returns this@FakeVehicle.control
    }

    fun setArmed(armed: Boolean, tMs: Long = heartbeat.value!!.tMs) {
        heartbeat.value = heartbeat.value!!.copy(armed = armed, tMs = tMs)
    }

    fun target(params: ParamService, calibration: CalibrationService = FakeCalibrationService()) =
        ConfigureTarget(vehicle, params, calibration)
}
