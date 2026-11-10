package com.altnautica.gcs.feature.home

import androidx.annotation.StringRes
import com.altnautica.gcs.core.link.node.NodeVitals
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.Reach

/** Full-screen surfaces launched from Home and the Quick menu. */
enum class HomeSurface { Fly, Plan, Setup, Parameters, Vehicle, Logs, Settings }

/** One launch card: [reason] says why a disabled surface is unavailable. */
data class SurfaceCard(val surface: HomeSurface, val enabled: Boolean, @param:StringRes val reason: Int?)

/**
 * The surfaces offered for [card] (the selected vehicle, or null when none), in
 * launch order. Fly, Setup and Parameters are hidden for nodes that never carry a
 * flight controller (Visor, Workstation); a disabled surface carries its reason.
 */
fun surfaces(card: FleetCard?): List<SurfaceCard> {
    val profile = card?.node?.profile
    val noFc = profile == NodeProfile.Visor || profile == NodeProfile.Workstation
    val hasFc = card != null && (profile == NodeProfile.Drone || Reach.DirectFc in card.node.reaches)
    val fcReason = if (card == null) R.string.home_reason_no_vehicle else R.string.home_reason_no_fc
    return buildList {
        if (!noFc) add(SurfaceCard(HomeSurface.Fly, card?.canFly == true, if (card?.canFly == true) null else flyReason(card)))
        add(SurfaceCard(HomeSurface.Plan, enabled = true, reason = null))
        if (!noFc) {
            add(SurfaceCard(HomeSurface.Setup, hasFc, if (hasFc) null else fcReason))
            add(SurfaceCard(HomeSurface.Parameters, hasFc, if (hasFc) null else fcReason))
        }
        add(SurfaceCard(HomeSurface.Vehicle, card != null, if (card != null) null else R.string.home_reason_no_vehicle))
        add(SurfaceCard(HomeSurface.Logs, enabled = true, reason = null))
        add(SurfaceCard(HomeSurface.Settings, enabled = true, reason = null))
    }
}

@StringRes
private fun flyReason(card: FleetCard?): Int = when {
    card == null -> R.string.home_reason_no_vehicle
    card.node.liveness == Liveness.Offline -> R.string.home_reason_offline
    card.node.profile == NodeProfile.GroundStation && card.node.carriedDroneId == null -> R.string.home_reason_no_carried
    card.node.profile == NodeProfile.Unknown && Reach.DirectFc !in card.node.reaches -> R.string.home_reason_no_fc
    else -> R.string.home_reason_no_link
}

/** What the hero's readiness line says for the selected vehicle. */
sealed interface HeroReadiness {
    /** Fly is open and battery, mode and GPS are all known. */
    data object Ready : HeroReadiness

    /** Fly is open but battery, mode or GPS has not arrived yet. */
    data object Connecting : HeroReadiness

    /** Fly is unavailable for [reason]. */
    data class Blocked(@param:StringRes val reason: Int) : HeroReadiness
}

/** True once battery, flight mode and GPS have all been reported. */
fun vitalsKnown(vitals: NodeVitals): Boolean =
    vitals.batteryPct != null && vitals.modeName != null && vitals.gpsFix != null

/**
 * The readiness line for [fly] with [vitals]: a disabled Fly card shows its reason;
 * an open one reads as ready only once the vitals are known, and as connecting before.
 */
fun heroReadiness(fly: SurfaceCard, vitals: NodeVitals): HeroReadiness = when {
    !fly.enabled -> fly.reason?.let { HeroReadiness.Blocked(it) } ?: HeroReadiness.Connecting
    !vitalsKnown(vitals) -> HeroReadiness.Connecting
    else -> HeroReadiness.Ready
}

/**
 * The node a surface opens on: Fly, Setup, Parameters and Vehicle need the selected
 * vehicle, Plan takes it when there is one, Logs and Settings are app-wide.
 */
fun HomeSurface.nodeArgument(selected: DeviceId?): DeviceId? = when (this) {
    HomeSurface.Fly, HomeSurface.Setup, HomeSurface.Parameters, HomeSurface.Vehicle, HomeSurface.Plan -> selected
    HomeSurface.Logs, HomeSurface.Settings -> null
}
