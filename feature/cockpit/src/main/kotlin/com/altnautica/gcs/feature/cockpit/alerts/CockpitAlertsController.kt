package com.altnautica.gcs.feature.cockpit.alerts

import com.altnautica.gcs.core.data.cockpit.CockpitSettings
import com.altnautica.gcs.core.model.BatteryState
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.EkfState
import com.altnautica.gcs.core.model.FenceStatus
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.SystemHealth
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.model.VibrationState
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.skills.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Cockpit alerts from telemetry, video and STATUSTEXT, plus their spoken callouts. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CockpitAlertsController(
    scope: CoroutineScope,
    private val clock: MonotonicClock,
    telemetry: StateFlow<VehicleTelemetry?>,
    videoState: StateFlow<VideoState>,
    criticalText: StateFlow<ReceivedText?>,
    prearmText: StateFlow<ReceivedText?>,
    settings: StateFlow<CockpitSettings>,
    tick500: StateFlow<Long>,
    announcer: AlertAnnouncer,
) {
    private val alertEngine = AlertEngine()

    val alerts: StateFlow<List<CockpitAlert>> = telemetry.flatMapLatest { t ->
        if (t == null) return@flatMapLatest flowOf(emptyList())
        combine(
            combine(t.heartbeat, t.systemHealth, t.battery, t.ekf, t.fence) { hb, sys, batt, ekf, fence -> AlertParts1(hb, sys, batt, ekf, fence) },
            combine(t.gps, t.vibration, videoState, criticalText, prearmText) { gps, vib, vs, crit, pre -> AlertParts2(gps, vib, vs is VideoState.Frozen, crit, pre) },
            settings,
            tick500,
        ) { a, b, s, _ ->
            val now = clock.nowMs()
            alertEngine.evaluate(
                AlertInputs(
                    armed = a.hb?.armed,
                    heartbeatMs = a.hb?.tMs,
                    systemHealth = a.sys,
                    batteryPct = a.batt?.remainingPct,
                    bands = s.batteryBands,
                    ekf = a.ekf,
                    fence = a.fence,
                    gps = b.gps,
                    vibration = b.vib,
                    videoFrozen = b.frozen,
                    criticalText = b.crit,
                    prearmText = b.pre,
                ),
                now,
            )
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            combine(alerts, settings) { a, s -> a to s }.collect { (a, s) -> announcer.onAlerts(a, s.audioCallouts) }
        }
        scope.launch {
            combine(telemetry.flatMapLatest { it?.heartbeat ?: flowOf(null) }.map { it?.mode?.name }.distinctUntilChanged(), settings) { m, s -> m to s }
                .collect { (mode, s) -> announcer.onModeChanged(mode, s.modeCallouts) }
        }
    }

    private data class AlertParts1(
        val hb: HeartbeatState?,
        val sys: SystemHealth?,
        val batt: BatteryState?,
        val ekf: EkfState?,
        val fence: FenceStatus?,
    )

    private data class AlertParts2(
        val gps: GpsState?,
        val vib: VibrationState?,
        val frozen: Boolean,
        val crit: ReceivedText?,
        val pre: ReceivedText?,
    )
}
