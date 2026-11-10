package com.altnautica.gcs.core.skills.builtin

import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.skills.SkillReason
import com.altnautica.gcs.core.skills.hud.FlyHereFc
import com.altnautica.gcs.core.skills.hud.FlyHereRoute
import com.altnautica.gcs.core.skills.hud.flyHereOffered

/** FC modes a failsafe flies; the same modes entered on an operator's command are not a failsafe. */
internal val FAILSAFE_MODES = setOf("RTL", "SMART_RTL", "AUTO_RTL", "LAND", "QRTL", "QLAND", "AUTO.RTL", "AUTO.LAND")

private const val MAV_STATE_CRITICAL = 5
private const val MAV_STATE_EMERGENCY = 6
private const val GUIDED_MODE = "GUIDED"

/** The pilot's state is present and fresh on its channel. */
internal fun SkillContext.pilotFresh(): Boolean = pilot != null && pilotLink?.stateFresh == true

/**
 * The flight controller flies a failsafe: it reports MAV_STATE critical or emergency, or a
 * fresh pilot says so (it sees every ground station's mode commands). Without a fresh pilot,
 * the FC is armed in a return or landing mode no operator here commanded.
 */
fun SkillContext.fcFailsafe(): Boolean {
    if (systemStatus == MAV_STATE_CRITICAL || systemStatus == MAV_STATE_EMERGENCY) return true
    if (pilotFresh()) return pilot!!.fcFailsafe
    val v = vehicle ?: return false
    val modeName = mode?.name?.uppercase() ?: return false
    return armed == true && modeName in FAILSAFE_MODES && !OperatorRecovery.commanded(v.id)
}

/**
 * On the ground as known: a fresh pilot's own report, else the FC's `EXTENDED_SYS_STATE`
 * landed state; null when neither is known.
 */
internal fun SkillContext.knownLanded(): Boolean? = if (pilotFresh()) pilot!!.landed else fcLanded

/** Fly Here's route from the shared offer; null when it is not offered. */
internal fun SkillContext.flyHereRoute(): FlyHereRoute? {
    val ageMs = if (pilotLink?.stateFresh == true) 0L else Long.MAX_VALUE
    val fc = FlyHereFc(
        guided = mode?.name.equals(GUIDED_MODE, ignoreCase = true),
        armed = armed == true,
        landed = knownLanded() ?: true,
    )
    return flyHereOffered(pilot, ageMs, fc)
}

/** Why Fly Here is not offered, most specific first. */
internal fun SkillContext.flyHereRefusal(): String = when {
    armed == null -> SkillReason.NO_FC_LINK
    armed == false -> SkillReason.NOT_ARMED
    (pilotFresh() && pilot!!.fcFailsafe) || fcFailsafe() -> SkillReason.FAILSAFE
    !mode?.name.equals(GUIDED_MODE, ignoreCase = true) -> SkillReason.FLY_HERE_NOT_GUIDED
    else -> SkillReason.FLY_HERE_LANDED
}
