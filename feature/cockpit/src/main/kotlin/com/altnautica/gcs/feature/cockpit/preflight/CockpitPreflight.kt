package com.altnautica.gcs.feature.cockpit.preflight

import com.altnautica.gcs.core.data.cockpit.CockpitSettings
import com.altnautica.gcs.core.model.BatteryState
import com.altnautica.gcs.core.model.EkfState
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.HomePosition
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.isLinkFresh
import com.altnautica.gcs.feature.cockpit.alerts.ReceivedText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/** The preflight checklist for the flown vehicle, re-evaluated on every telemetry change and tick. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun cockpitPreflight(
    scope: CoroutineScope,
    clock: MonotonicClock,
    telemetry: StateFlow<VehicleTelemetry?>,
    settings: StateFlow<CockpitSettings>,
    prearmText: StateFlow<ReceivedText?>,
    tick500: StateFlow<Long>,
): StateFlow<PreflightResult> = telemetry.flatMapLatest { t ->
    if (t == null) {
        combine(settings, tick500) { s, _ -> PreflightChecklist.evaluate(PreflightInputs(null, null, s.batteryBands, null, null, false, null), clock.nowMs()) }
    } else {
        combine(
            combine(t.gps, t.battery, t.ekf, t.home) { gps, batt, ekf, home -> listOf(gps, batt, ekf, home) },
            t.heartbeat,
            settings,
            prearmText,
            tick500,
        ) { parts, hb, s, prearm, _ ->
            val now = clock.nowMs()
            PreflightChecklist.evaluate(
                PreflightInputs(
                    gps = parts[0] as GpsState?,
                    batteryPct = (parts[1] as BatteryState?)?.remainingPct,
                    bands = s.batteryBands,
                    ekf = parts[2] as EkfState?,
                    home = parts[3] as HomePosition?,
                    linkFresh = isLinkFresh(hb, now),
                    lastPrearmMs = prearm?.atMs,
                ),
                now,
            )
        }
    }
}.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, PreflightResult(emptyList()))
