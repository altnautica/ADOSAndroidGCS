package com.altnautica.gcs.feature.cockpit.preflight

import android.os.Environment
import android.os.StatFs
import com.altnautica.gcs.core.data.cockpit.CockpitSettings
import com.altnautica.gcs.core.model.BatteryState
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.EkfState
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.HomePosition
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.isLinkFresh
import com.altnautica.gcs.feature.cockpit.alerts.ReceivedText
import com.altnautica.gcs.feature.cockpit.autonomy.NavChip
import com.altnautica.gcs.feature.cockpit.sticksShown
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.math.round

/**
 * What the checklist reads besides telemetry: the pilot's [readiness] and [controlMode], the
 * sticks and the nav chip. Only those pilot fields, so pilot frames that differ in their
 * receive time or input age leave it equal and re-evaluate nothing.
 */
internal data class PreflightContext(
    val readiness: List<ReadinessItem>,
    val controlMode: ControlMode?,
    val controllerConnected: Boolean,
    val navChip: NavChip?,
) {
    companion object {
        fun of(pilot: PilotState?, controllerConnected: Boolean, navChip: NavChip?) =
            PreflightContext(pilot?.readiness.orEmpty(), pilot?.controlMode, controllerConnected, navChip)
    }
}

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

/** How often the data volume is read for the storage check. */
internal const val STORAGE_POLL_MS = 10_000L

/**
 * The data volume's used percent in whole percent, read every [periodMs] in [readContext]
 * (off the main thread in the app), so no checklist evaluation touches the file system.
 */
internal fun storageUsedPct(
    scope: CoroutineScope,
    readContext: CoroutineContext,
    periodMs: Long = STORAGE_POLL_MS,
    read: () -> Double? = ::dataVolumeUsedPct,
): StateFlow<Double?> = flow {
    while (true) {
        emit(withContext(readContext) { read() }?.let { round(it) })
        delay(periodMs)
    }
}.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

/**
 * The preflight checklist for the flown vehicle, re-evaluated on every input change and tick.
 * [storageWarnPct] is the spec's `status_pill.storage_warn_pct`; [storage] is [storageUsedPct].
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
    storage: StateFlow<Double?>,
): StateFlow<PreflightResult> = telemetry.flatMapLatest { t ->
    if (t == null) {
        combine(settings, context, storage, tick500) { s, c, st, _ ->
            PreflightChecklist.evaluate(inputs(null, null, null, null, false, false, null, s, c, st, storageWarnPct), clock.nowMs())
        }
    } else {
        // GPS, battery, EKF, home and the heartbeat are read on the tick, not on each sample, so
        // the result (and the band's checklist count) changes on the tick only.
        combine(
            combine(settings, prearmText, context) { s, p, c -> Triple(s, p, c) },
            storage,
            tick500,
        ) { (s, prearm, c), st, _ ->
            val now = clock.nowMs()
            PreflightChecklist.evaluate(
                inputs(
                    gps = t.gps.value,
                    battery = t.battery.value,
                    ekf = t.ekf.value,
                    home = t.home.value,
                    ekfOriginSet = t.ekfOriginSet.value,
                    linkFresh = isLinkFresh(t.heartbeat.value, now),
                    lastPrearmMs = prearm?.atMs,
                    settings = s,
                    context = c,
                    storage = st,
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
    ekfOriginSet: Boolean,
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
    ekfOriginSet = ekfOriginSet,
    linkFresh = linkFresh,
    lastPrearmMs = lastPrearmMs,
    readiness = context.readiness,
    controlMode = context.controlMode,
    controllerConnected = context.controllerConnected,
    navChip = context.navChip,
    storageUsedPct = storage,
    storageWarnPct = storageWarnPct,
)
