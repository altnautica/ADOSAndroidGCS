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
import com.altnautica.gcs.feature.cockpit.sticksShown
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
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

/**
 * Whether something can fly the sticks for the pre-flight controller check: a pad, or the
 * on-screen sticks when enabled and shown at this [width] (`on_screen_sticks.classes`).
 */
internal fun sticksAvailable(pad: Boolean, onScreen: Boolean, width: WidthClass?, classes: List<String>): Boolean =
    pad || (width != null && sticksShown(onScreen, width, classes))

/**
 * Percent of the app's data volume used, where recordings land; null when the volume cannot be
 * read. It is the phone's own volume, not the vehicle's disk, so the pill only warns on it.
 */
internal fun dataVolumeUsedPct(): Double? = runCatching {
    val fs = StatFs(Environment.getDataDirectory().path)
    usedPct(fs.totalBytes, fs.availableBytes)
}.getOrNull()

/** Percent of [totalBytes] not [availableBytes]; null for an empty volume. */
internal fun usedPct(totalBytes: Long, availableBytes: Long): Double? =
    if (totalBytes <= 0L) null else (totalBytes - availableBytes).coerceAtLeast(0L) * 100.0 / totalBytes

/**
 * The preflight checklist for the flown vehicle, re-evaluated on every input change and tick.
 * [storageWarnPct] is the spec's `status_pill.storage_warn_pct`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun cockpitPreflight(
    scope: CoroutineScope,
    clock: MonotonicClock,
    telemetry: StateFlow<VehicleTelemetry?>,
    settings: StateFlow<CockpitSettings>,
    prearmText: StateFlow<ReceivedText?>,
    context: StateFlow<PreflightContext>,
    tick500: StateFlow<Long>,
    storageWarnPct: Int,
    storageUsedPct: () -> Double? = ::dataVolumeUsedPct,
): StateFlow<PreflightResult> = telemetry.flatMapLatest { t ->
    if (t == null) {
        combine(settings, context, tick500) { s, c, _ ->
            PreflightChecklist.evaluate(inputs(null, null, null, null, false, null, s, c, storageUsedPct(), storageWarnPct), clock.nowMs())
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
                    storage = storageUsedPct(),
                    storageWarnPct = storageWarnPct,
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
    storage: Double?,
    storageWarnPct: Int,
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
    storageUsedPct = storage,
    storageWarnPct = storageWarnPct,
)
