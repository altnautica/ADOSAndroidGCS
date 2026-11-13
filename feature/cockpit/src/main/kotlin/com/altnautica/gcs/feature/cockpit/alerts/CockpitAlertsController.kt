package com.altnautica.gcs.feature.cockpit.alerts

import com.altnautica.gcs.core.data.cockpit.CockpitSettings
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.VehicleTelemetry
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
    /** The latest one-off cockpit event (stick control turned off on its own). */
    raised: StateFlow<RaisedAlert?>,
    /** The controller battery is low while stick control is on. */
    controllerBatteryLow: StateFlow<Boolean>,
) {
    private val alertEngine = AlertEngine()

    /**
     * The alerts, evaluated on the 2 Hz tick from each topic's newest value rather than on
     * every message of ten topics. Discrete events that must not wait for the tick evaluate at
     * once: an arm change or the first heartbeat, video freezing or recovering, an FC text,
     * a raised cockpit event, the controller battery and a settings change.
     */
    val alerts: StateFlow<List<CockpitAlert>> = telemetry.flatMapLatest { t ->
        if (t == null) return@flatMapLatest flowOf(emptyList())
        val discrete = combine(
            t.heartbeat.map { it?.armed }.distinctUntilChanged(),
            videoState.map { it is VideoState.Frozen }.distinctUntilChanged(),
            combine(criticalText, prearmText) { c, p -> c to p },
            combine(settings, raised, controllerBatteryLow) { s, r, low -> Triple(s, r, low) },
        ) { _, frozen, (crit, pre), (s, raisedAlert, batteryLow) -> Discrete(frozen, crit, pre, s, raisedAlert, batteryLow) }
        combine(discrete, tick500) { d, _ ->
            val hb = t.heartbeat.value
            alertEngine.evaluate(
                AlertInputs(
                    armed = hb?.armed,
                    heartbeatMs = hb?.tMs,
                    systemHealth = t.systemHealth.value,
                    batteryPct = t.battery.value?.remainingPct,
                    bands = d.settings.batteryBands,
                    ekf = t.ekf.value,
                    fence = t.fence.value,
                    gps = t.gps.value,
                    vibration = t.vibration.value,
                    videoFrozen = d.frozen,
                    criticalText = d.crit,
                    prearmText = d.pre,
                    raised = d.raised,
                    controllerBatteryLow = d.batteryLow,
                ),
                clock.nowMs(),
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

    /** The discrete inputs that evaluate at once. */
    private data class Discrete(
        val frozen: Boolean,
        val crit: ReceivedText?,
        val pre: ReceivedText?,
        val settings: CockpitSettings,
        val raised: RaisedAlert?,
        val batteryLow: Boolean,
    )
}
