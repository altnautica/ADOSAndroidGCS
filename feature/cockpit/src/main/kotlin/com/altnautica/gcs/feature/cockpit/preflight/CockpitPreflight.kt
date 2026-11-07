package com.altnautica.gcs.feature.cockpit.preflight

import android.os.Environment
import android.os.StatFs
import com.altnautica.gcs.core.data.cockpit.CockpitSettings
import com.altnautica.gcs.core.model.BatteryState
import com.altnautica.gcs.core.model.EkfState
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.HomePosition
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.isLinkFresh
import com.altnautica.gcs.feature.cockpit.alerts.ReceivedText
import com.altnautica.gcs.feature.cockpit.autonomy.NavChip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/** What the checklist reads besides telemetry: the pilot, the sticks, the nav chip and storage. */
internal data class PreflightContext(
    val pilot: PilotState?,
    val controllerConnected: Boolean,
    val navChip: NavChip?,
)

/** Free bytes on the app's data volume, where recordings land; null when the volume cannot be read. */
internal fun dataVolumeFreeBytes(): Long? = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrNull()

/** The preflight checklist for the flown vehicle, re-evaluated on every input change and tick. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun cockpitPreflight(
    scope: CoroutineScope,
    clock: MonotonicClock,
    telemetry: StateFlow<VehicleTelemetry?>,
    settings: StateFlow<CockpitSettings>,
    prearmText: StateFlow<ReceivedText?>,
    context: StateFlow<PreflightContext>,
    tick500: StateFlow<Long>,
    storageFreeBytes: () -> Long? = ::dataVolumeFreeBytes,
): StateFlow<PreflightResult> = telemetry.flatMapLatest { t ->
    if (t == null) {
        combine(settings, context, tick500) { s, c, _ ->
            PreflightChecklist.evaluate(inputs(null, null, null, null, false, null, s, c, storageFreeBytes()), clock.nowMs())
        }
    } else {
        combine(
            combine(t.gps, t.battery, t.ekf, t.home) { gps, batt, ekf, home -> listOf(gps, batt, ekf, home) },
            t.heartbeat,
            combine(settings, prearmText, context) { s, p, c -> Triple(s, p, c) },
            tick500,
        ) { parts, hb, (s, prearm, c), _ ->
            val now = clock.nowMs()
            PreflightChecklist.evaluate(
                inputs(
                    gps = parts[0] as GpsState?,
                    battery = parts[1] as BatteryState?,
                    ekf = parts[2] as EkfState?,
                    home = parts[3] as HomePosition?,
                    linkFresh = isLinkFresh(hb, now),
                    lastPrearmMs = prearm?.atMs,
                    settings = s,
                    context = c,
                    storage = storageFreeBytes(),
                ),
                now,
            )
        }
    }
}.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, PreflightResult(emptyList()))

private fun inputs(
    gps: GpsState?,
    battery: BatteryState?,
    ekf: EkfState?,
    home: HomePosition?,
    linkFresh: Boolean,
    lastPrearmMs: Long?,
    settings: CockpitSettings,
    context: PreflightContext,
    storage: Long?,
) = PreflightInputs(
    gps = gps,
    batteryPct = battery?.remainingPct,
    bands = settings.batteryBands,
    ekf = ekf,
    home = home,
    linkFresh = linkFresh,
    lastPrearmMs = lastPrearmMs,
    readiness = context.pilot?.readiness.orEmpty(),
    controlMode = context.pilot?.controlMode,
    controllerConnected = context.controllerConnected,
    navChip = context.navChip,
    storageFreeBytes = storage,
)
