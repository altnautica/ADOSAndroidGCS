package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotState

/** What the surface knows of the flight controller itself. */
data class FlyHereFc(val guided: Boolean, val armed: Boolean, val landed: Boolean)

/** Where a Fly Here point goes: the pilot's `goto`, or the FC's reposition in GUIDED. */
enum class FlyHereRoute(val wire: String) { Pilot("pilot"), Fc("fc") }

/**
 * Whether Fly Here is offered now and by which route, from a pilot state [pilotAgeMs] old and
 * the FC's own state (web module `hud/fly-here-offer.ts`, held to `fly-here-cases.json`).
 * The pilot route needs a fresh state under ADOS control that offers `goto`, armed, airborne
 * and outside an FC failsafe (the pilot refuses a goto then); else the FC route while the FC
 * flies GUIDED, armed and airborne; else null.
 */
fun flyHereOffered(p: PilotState?, pilotAgeMs: Long, fc: FlyHereFc?, staleAfterMs: Long = PilotState.STALE_MS): FlyHereRoute? {
    if (p != null &&
        pilotAgeMs < staleAfterMs &&
        p.controlMode == ControlMode.Ados &&
        PilotAction.GOTO in p.surfaceOps &&
        p.armed &&
        !p.landed &&
        !p.fcFailsafe
    ) {
        return FlyHereRoute.Pilot
    }
    if (fc != null && fc.guided && fc.armed && !fc.landed) return FlyHereRoute.Fc
    return null
}
