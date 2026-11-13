package com.altnautica.gcs.feature.cockpit.autonomy

import com.altnautica.gcs.core.model.EkfState
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.feature.cockpit.UI_STOP_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * The nav chip's inputs, sampled on [tick] (2 Hz) and at once on a [commandedSet] or
 * [ekfParams] change; EKF, GPS and nav messages are only read then, never per message. The
 * EKF age is bucketed to the stale threshold [NavSource.resolve] compares it with (0 or
 * [NavSource.EKF_STALE_MS]), so readings that change nothing shown leave the value equal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun navInputsOnTick(
    scope: CoroutineScope,
    clock: MonotonicClock,
    telemetry: StateFlow<VehicleTelemetry?>,
    nav: StateFlow<Stamped<NavState>?>,
    tick: StateFlow<Long>,
    commandedSet: StateFlow<Int?>,
    ekfParams: StateFlow<Map<String, Int>>,
): StateFlow<NavInputs?> {
    val samples: StateFlow<Triple<EkfState?, GpsState?, Stamped<NavState>?>> = combine(
        telemetry.flatMapLatest { t -> t?.let { combine(it.ekf, it.gps) { e, g -> e to g } } ?: flowOf(null to null) },
        nav,
    ) { (ekf, gps), n -> Triple(ekf, gps, n) }.stateIn(scope, SharingStarted.Eagerly, Triple(null, null, null))
    return combine(tick, commandedSet, ekfParams) { _, commanded, params ->
        val (ekf, gps, n) = samples.value
        val now = clock.nowMs()
        NavInputs(
            ekfFlags = ekf?.flags,
            ekfAgeMs = ekf?.let { if (now - it.tMs >= NavSource.EKF_STALE_MS) NavSource.EKF_STALE_MS else 0L },
            nav = n?.value,
            navFresh = n != null && now - n.atMs <= TargetState.FOLLOW_STALE_MS,
            commandedSet = commanded,
            params = params,
            gpsFixType = gps?.fix?.let { CockpitAutonomy.fixType(it) },
            sats = gps?.satellites,
            hdop = gps?.hdop,
        )
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), null)
}
