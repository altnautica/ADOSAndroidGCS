package com.altnautica.gcs.feature.cockpit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.altnautica.gcs.core.map.GeoMath
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.MapMover
import com.altnautica.gcs.core.map.MapMoverKind
import com.altnautica.gcs.core.model.LocalPosition
import com.altnautica.gcs.core.model.MoverKind
import com.altnautica.gcs.core.model.TrackedMover
import com.altnautica.gcs.core.skills.hud.freshPilot
import com.altnautica.gcs.feature.cockpit.chrome.rememberOnBeat
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.hud.MinimapMovers
import kotlin.math.atan2
import kotlin.math.hypot

/** The pilot's tracked movers as the cockpit map shows them: its [marks] and its movers [line]. */
internal data class MapMoversView(val marks: List<MapMover>, val line: String?)

/**
 * The pilot's movers for the cockpit map, on the flight screen's beat and only while the
 * pilot frame is fresh: placed from the vehicle's map position by their offset from its local
 * position, and counted in the movers line (nearest person from the vehicle's local position);
 * a local position over [MinimapMovers.LOCAL_POSITION_STALE_MS] old places none and leaves the count only.
 */
@Composable
internal fun rememberMapMovers(vm: CockpitViewModel): State<MapMoversView> {
    val strings = LocalCockpitStrings.current
    return rememberOnBeat {
        val now = vm.autonomy.nowMs()
        val movers = freshPilot(vm.autonomy.feed.pilot.value, now)?.movers.orEmpty()
        val local = MinimapMovers.freshLocal(vm.telemetry.value?.localPosition?.value, now)
        MapMoversView(
            mapMovers(movers, vm.minimap.value.vehicle?.position, local),
            MinimapMovers.line(movers, local?.northM, local?.eastM, strings::format),
        )
    }
}

/**
 * The pilot's tracked [movers] on the map: each placed from the vehicle's map position
 * [vehicle] by its offset (local NED) from the vehicle's own local position [local], so no
 * EKF origin is needed; its arrow tip [MinimapMovers.ARROW_S] seconds on. Empty without
 * both positions.
 */
fun mapMovers(movers: List<TrackedMover>, vehicle: GeoPoint?, local: LocalPosition?): List<MapMover> {
    if (movers.isEmpty() || vehicle == null || local == null) return emptyList()
    return movers.map { m ->
        val dn = (m.northM - local.northM).toDouble()
        val de = (m.eastM - local.eastM).toDouble()
        val moving = hypot(m.velNorthMps, m.velEastMps) >= MinimapMovers.MIN_ARROW_MPS
        val tip = if (moving) {
            offset(vehicle, dn + m.velNorthMps * MinimapMovers.ARROW_S, de + m.velEastMps * MinimapMovers.ARROW_S)
        } else {
            null
        }
        MapMover("mover-${m.id}", offset(vehicle, dn, de), m.kind.mapKind(), tip, m.sigmaM.toDouble(), m.stale)
    }
}

private fun offset(from: GeoPoint, northM: Double, eastM: Double): GeoPoint =
    GeoMath.destination(from, Math.toDegrees(atan2(eastM, northM)), hypot(northM, eastM))

private fun MoverKind.mapKind(): MapMoverKind = when (this) {
    MoverKind.Person -> MapMoverKind.Person
    MoverKind.Vehicle -> MapMoverKind.Vehicle
    MoverKind.Unknown -> MapMoverKind.Unknown
}
