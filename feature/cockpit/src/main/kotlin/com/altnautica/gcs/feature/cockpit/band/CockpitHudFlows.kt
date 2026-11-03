package com.altnautica.gcs.feature.cockpit.band

import com.altnautica.gcs.core.data.cockpit.CockpitSettings
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.MapVehicle
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.heartbeatAgeMs
import com.altnautica.gcs.core.video.RecordingState
import com.altnautica.gcs.feature.cockpit.BandState
import com.altnautica.gcs.feature.cockpit.BatteryBand
import com.altnautica.gcs.feature.cockpit.Geo
import com.altnautica.gcs.feature.cockpit.MinimapState
import com.altnautica.gcs.feature.cockpit.StickChip
import com.altnautica.gcs.feature.cockpit.StripValues
import com.altnautica.gcs.feature.cockpit.UI_STOP_MS
import com.altnautica.gcs.feature.cockpit.alerts.AlertEngine
import com.altnautica.gcs.feature.cockpit.alerts.LinkHealth
import com.altnautica.gcs.feature.cockpit.badge
import com.altnautica.gcs.feature.cockpit.hud.HudMath
import com.altnautica.gcs.feature.cockpit.preflight.PreflightResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The safety band, telemetry strip and minimap, all sampled on the shared 2 Hz tick. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CockpitHudFlows(
    scope: CoroutineScope,
    private val clock: MonotonicClock,
    telemetry: StateFlow<VehicleTelemetry?>,
    node: StateFlow<NodeSummary?>,
    vehicle: StateFlow<Vehicle?>,
    settings: StateFlow<CockpitSettings>,
    videoState: StateFlow<VideoState>,
    stick: StateFlow<StickChip>,
    preflight: StateFlow<PreflightResult>,
    recording: StateFlow<RecordingState?>,
    tick500: StateFlow<Long>,
) {
    private val armedSince = MutableStateFlow<Long?>(null)

    init {
        scope.launch {
            telemetry.flatMapLatest { it?.heartbeat ?: flowOf(null) }
                .map { it?.armed }.distinctUntilChanged()
                .collect { armed ->
                    armedSince.value = when (armed) {
                        true -> armedSince.value ?: clock.nowMs()
                        else -> null
                    }
                }
        }
    }

    val band: StateFlow<BandState> = telemetry.flatMapLatest { t ->
        val coarse = if (t == null) flowOf(BandTelemetry.Empty) else combine(
            t.heartbeat, t.battery, t.linkQuality, tick500,
        ) { hb, batt, lq, _ ->
            val now = clock.nowMs()
            val age = hb?.let { heartbeatAgeMs(it.tMs, now) }
            BandTelemetry(
                armed = hb?.armed,
                mode = hb?.mode?.name,
                battery = batt?.remainingPct?.takeIf { it >= 0 },
                ttr = batt?.timeToReserveS,
                link = AlertEngine.linkHealth(hb?.tMs, now),
                ageDeci = age?.let { (it / 100).toInt().coerceAtMost(999) },
                rssi = lq?.rssiDbm,
                lq = lq?.lqPct,
            )
        }
        coarse
    }.combine(
        combine(node, vehicle.flatMapLatest { it?.reach ?: flowOf(null) }, settings, videoState, stick) { n, reach, s, vs, st ->
            BandContext(n, reach, s, vs, st)
        },
    ) { t, c -> t to c }
        .combine(combine(preflight, recording, armedSince) { p, r, a -> Triple(p, r, a) }) { (t, c), (p, r, a) ->
            BandState(
                nodeName = c.node?.name,
                profile = c.node?.profile ?: NodeProfile.Unknown,
                reach = c.reach?.badge(),
                armed = t.armed,
                modeName = t.mode,
                batteryPct = t.battery,
                timeToReserveS = t.ttr,
                batteryLevel = batteryBand(t.battery, c.settings),
                link = t.link,
                linkAgeDeciS = t.ageDeci,
                rssiDbm = t.rssi,
                lqPct = t.lq,
                video = c.video,
                stick = c.stick,
                preflightPassed = p.passed,
                preflightTotal = p.total,
                recordingStartedAtMs = (r as? RecordingState.Recording)?.startedAtMs,
                armedSinceMs = a,
            )
        }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), BandState.Empty)

    private fun batteryBand(pct: Int?, s: CockpitSettings): BatteryBand = when {
        pct == null -> BatteryBand.Unknown
        pct < s.batteryBands.criticalPct -> BatteryBand.Critical
        pct < s.batteryBands.lowPct -> BatteryBand.Low
        else -> BatteryBand.Good
    }

    val strip: StateFlow<StripValues> = telemetry.flatMapLatest { t ->
        if (t == null) return@flatMapLatest flowOf(EMPTY_STRIP)
        tick500.map {
            val pos = t.position.value
            val home = t.home.value
            val vfr = t.vfr.value
            val gps = t.gps.value
            val mission = t.missionProgress.value
            val homeVec = if (pos != null && home != null) Geo.distanceBearing(pos.latDeg, pos.lonDeg, home.latDeg, home.lonDeg) else null
            StripValues(
                homeDistanceM = homeVec?.first,
                homeBearingDeg = homeVec?.second,
                climbMs = vfr?.climbMs,
                throttlePct = vfr?.throttlePct,
                missionSeq = mission?.currentSeq,
                missionTotal = mission?.total,
                satellites = gps?.satellites,
                hdop = gps?.hdop,
            )
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), EMPTY_STRIP)

    /** Minimap vehicle, home and trail, sampled at 2 Hz; the trail keeps points at least 2 m apart. */
    val minimap: StateFlow<MinimapState> = telemetry.flatMapLatest { t ->
        if (t == null) return@flatMapLatest flowOf(MinimapState(null, null, emptyList()))
        val trail = ArrayDeque<GeoPoint>()
        tick500.map { now ->
            val pos = t.position.value
            val point = pos?.let { GeoPoint(it.latDeg, it.lonDeg) }
            if (point != null) {
                val last = trail.lastOrNull()
                if (last == null || Geo.distanceBearing(last.latDeg, last.lonDeg, point.latDeg, point.lonDeg).first >= TRAIL_MIN_STEP_M) {
                    trail.addLast(point)
                    if (trail.size > TRAIL_MAX_POINTS) trail.removeFirst()
                }
            }
            val heading = t.vfr.value?.headingDeg
            MinimapState(
                vehicle = point?.let { MapVehicle(it, heading, stale = now - pos.tMs > HudMath.STALE_MS) },
                home = t.home.value?.let { GeoPoint(it.latDeg, it.lonDeg) },
                trail = trail.toList(),
            )
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), MinimapState(null, null, emptyList()))

    private val _minimapSwapped = MutableStateFlow(false)

    /** True when the map fills the screen and the video sits in the inset. */
    val minimapSwapped: StateFlow<Boolean> = _minimapSwapped.asStateFlow()

    fun toggleMinimapSwap() {
        _minimapSwapped.update { !it }
    }

    private data class BandTelemetry(
        val armed: Boolean?,
        val mode: String?,
        val battery: Int?,
        val ttr: Int?,
        val link: LinkHealth,
        val ageDeci: Int?,
        val rssi: Int?,
        val lq: Int?,
    ) {
        companion object {
            val Empty = BandTelemetry(null, null, null, null, LinkHealth.None, null, null, null)
        }
    }

    private data class BandContext(
        val node: NodeSummary?,
        val reach: Reach?,
        val settings: CockpitSettings,
        val video: VideoState,
        val stick: StickChip,
    )

    private companion object {
        const val TRAIL_MIN_STEP_M = 2f
        const val TRAIL_MAX_POINTS = 600
        val EMPTY_STRIP = StripValues(null, null, null, null, null, null, null, null)
    }
}
