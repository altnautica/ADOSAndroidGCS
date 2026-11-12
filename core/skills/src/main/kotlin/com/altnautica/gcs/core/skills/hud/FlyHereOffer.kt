package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotState

/** What the surface knows of the flight controller itself. */
data class FlyHereFc(val guided: Boolean, val armed: Boolean, val landed: Boolean)

/** Where a Fly Here point goes: the pilot's `goto`, or the FC's reposition in GUIDED. */
enum class FlyHereRoute(val wire: String) { Pilot("pilot"), Fc("fc") }

/** Why Fly Here is not offered; worded by the `refusal.<wire>` spec string. */
enum class FlyHereReason(val wire: String) {
    Failsafe("failsafe"),
    Landed("landed"),
    PilotStale("pilot_stale"),
    ControlMode("control_mode"),
    NotGuided("not_guided"),
    ;

    /** The spec string that words this reason. */
    val refusalKey: String get() = "refusal.$wire"
}

/** The Fly Here offer: its [route], or null with the [reason] when one is known. */
data class FlyHereOffer(val route: FlyHereRoute?, val reason: FlyHereReason? = null)

/**
 * Whether Fly Here is offered now and by which route, and why not, from a pilot state
 * [pilotAgeMs] old and the FC's own state (web module `hud/fly-here-offer.ts`, held to
 * `fly-here-cases.json`). The pilot route needs a fresh state under ADOS control that offers
 * `goto`, known armed and airborne, outside an FC failsafe (the pilot refuses a goto then);
 * else the FC route while the FC flies GUIDED, armed and airborne, unless a fresh pilot state
 * reports an FC failsafe (a reposition would fight the failsafe RTL or land). Otherwise the
 * reason, so a map long-press can say it instead of opening a dialog. An unknown arm state
 * (the pilot has no FC link) is possibly airborne: never [FlyHereReason.Landed].
 */
fun flyHereOffered(p: PilotState?, pilotAgeMs: Long, fc: FlyHereFc?, staleAfterMs: Long = PilotState.STALE_MS): FlyHereOffer {
    val fresh = pilotAgeMs < staleAfterMs
    val gotoAdvertised = p != null && PilotAction.GOTO in p.surfaceOps
    if (p != null && fresh && p.controlMode == ControlMode.Ados && gotoAdvertised && p.knownAirborne && !p.fcFailsafe) {
        return FlyHereOffer(FlyHereRoute.Pilot)
    }
    val failsafe = p != null && fresh && p.fcFailsafe
    if (fc != null && !failsafe && fc.guided && fc.armed && !fc.landed) return FlyHereOffer(FlyHereRoute.Fc)
    if (p != null && !fresh) return FlyHereOffer(null, FlyHereReason.PilotStale)
    if (p != null) {
        if (p.fcFailsafe) return FlyHereOffer(null, FlyHereReason.Failsafe)
        if (p.armed == false || p.landed == true) return FlyHereOffer(null, FlyHereReason.Landed)
        if (fc == null && gotoAdvertised && p.controlMode != ControlMode.Ados) return FlyHereOffer(null, FlyHereReason.ControlMode)
    }
    if (fc != null) {
        if (!fc.armed || fc.landed) return FlyHereOffer(null, FlyHereReason.Landed)
        if (!fc.guided) return FlyHereOffer(null, FlyHereReason.NotGuided)
    }
    return FlyHereOffer(null)
}
