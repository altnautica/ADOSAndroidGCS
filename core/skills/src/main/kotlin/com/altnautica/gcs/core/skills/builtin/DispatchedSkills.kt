package com.altnautica.gcs.core.skills.builtin

import com.altnautica.gcs.core.model.ArmRequirement
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.model.ConfirmPolicy
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillState
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.skills.SkillReason
import com.altnautica.gcs.core.skills.hud.FlyHereRoute

// The built-ins outside the spec's table: the stick-profile keys and the skills dispatched
// from their own controls (the Mode sheet, the map, the nav-source chip).

/** Switches the onboard pilot's stick profile (F1–F3); needs the pilot channel. */
class ProfileSkill(val profile: PilotProfile, label: String) : BuiltinSkill(
    id = idFor(PilotAction.profile(profile).name),
    labelKey = label,
    icon = "speed",
    category = SkillCategory.Flight,
    armRequirement = ArmRequirement.Any,
    confirm = null,
) {
    override fun state(ctx: SkillContext): SkillState {
        val link = ctx.pilotLink ?: return disabledState(SkillReason.PILOT_UNAVAILABLE)
        if (!link.connected.value) return disabledState(SkillReason.NO_FC_LINK)
        return IDLE_STATE.copy(active = ctx.pilot?.profile == profile)
    }

    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>): CommandResult =
        ctx.sendPilot(PilotAction.profile(profile))

    companion object {
        /** The skill id of a `profile:<name>` action is the action name itself. */
        fun idFor(action: String): String = action
    }
}

/** Sets an arbitrary mode named by [SkillArgs.TARGET_MODE]; driven from the Mode sheet's FC mode list. */
object SetModeSkill : BuiltinSkill(
    id = "set-mode", labelKey = "skills.setMode", icon = "tune", category = SkillCategory.Flight,
    armRequirement = ArmRequirement.Any,
    confirm = null,
    bindable = false,
), ArgumentConfirm {
    private fun target(args: Map<String, Any?>): String? = (args[SkillArgs.TARGET_MODE] as? String)?.takeIf { it.isNotBlank() }

    override fun confirmFor(args: Map<String, Any?>): ConfirmPolicy? = target(args)?.let(::modeChangeConfirm)

    override fun state(ctx: SkillContext) = disabledIfNoLink(ctx) ?: IDLE_STATE

    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>): CommandResult {
        val name = target(args) ?: return CommandResult.Rejected(-1, SkillReason.MODE_UNAVAILABLE)
        val mode = ctxModes(ctx).firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return CommandResult.Rejected(-1, SkillReason.MODE_UNAVAILABLE)
        val result = send(ctx, VehicleCommand.SetMode(mode))
        if (mode.name.uppercase() in FAILSAFE_MODES) OperatorRecovery.noteIfSent(ctx.vehicle?.id, result)
        return result
    }
}

/** The pilot's goto refusal codes, each with its own operator sentence; any other code is shown verbatim. */
private val GOTO_REFUSALS = mapOf(
    "bad_args" to SkillReason.GOTO_BAD_ARGS,
    "not_armed" to SkillReason.GOTO_NOT_ARMED,
    "landed" to SkillReason.GOTO_LANDED,
    "tuning" to SkillReason.GOTO_TUNING,
    "no_home" to SkillReason.GOTO_NO_HOME,
    "launching" to SkillReason.GOTO_LAUNCHING,
    "control_mode" to SkillReason.GOTO_CONTROL_MODE,
    "failsafe" to SkillReason.GOTO_FAILSAFE,
)

/**
 * Flies to a map point at a relative altitude; dispatched from the map's own control, behind
 * a hold confirm. The pilot's `goto` flies it when the shared Fly Here offer picks the pilot
 * route, the FC's guided goto otherwise; a stale pilot would only answer "no answer".
 */
object FlyHereSkill : BuiltinSkill(
    id = "fly-here", labelKey = "skills.flyHere", icon = "near_me", category = SkillCategory.Flight,
    armRequirement = ArmRequirement.Armed,
    confirm = builtinConfirm("flyHere", ConfirmGesture.Hold, holdMs = 800, danger = false),
    bindable = false,
) {
    override fun state(ctx: SkillContext): SkillState {
        val route = ctx.flyHereRoute()
        disabledIfNoLink(ctx, route == FlyHereRoute.Pilot)?.let { return it }
        return if (route == null) disabledState(ctx.flyHereRefusal()) else IDLE_STATE
    }

    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>): CommandResult {
        val lat = (args[SkillArgs.LAT] as? Number)?.toDouble()
        val lon = (args[SkillArgs.LON] as? Number)?.toDouble()
        val alt = (args[SkillArgs.ALTITUDE_M] as? Number)?.toFloat()
        if (lat == null || lon == null || alt == null || !lat.isFinite() || !lon.isFinite() ||
            alt < FlyHereAltitude.MIN_M || alt > FlyHereAltitude.MAX_M
        ) {
            return CommandResult.Rejected(-1, SkillReason.FLY_HERE_TARGET)
        }
        val route = ctx.flyHereRoute() ?: return CommandResult.Denied(ctx.flyHereRefusal())
        if (route == FlyHereRoute.Pilot) {
            val r = ctx.sendPilot(PilotAction(PilotAction.GOTO, mapOf(PILOT_LAT to lat, PILOT_LON to lon, PILOT_ALT_M to alt)))
            return if (r is CommandResult.Rejected) r.copy(reason = GOTO_REFUSALS[r.reason] ?: r.reason) else r
        }
        return send(ctx, VehicleCommand.FlyTo(lat, lon, alt))
    }

    private const val PILOT_LAT = "lat"
    private const val PILOT_LON = "lon"
    private const val PILOT_ALT_M = "alt_m"
}

/** Makes an EKF3 source set active; dispatched from the nav-source chip's popover. */
object EkfSourceSetSkill : BuiltinSkill(
    id = "ekf-source-set", labelKey = "skills.ekfSourceSet", icon = "swap_horiz", category = SkillCategory.Flight,
    armRequirement = ArmRequirement.Any,
    confirm = builtinConfirm("ekfSourceSet", ConfirmGesture.Hold, holdMs = 800, danger = false),
    bindable = false,
) {
    override fun state(ctx: SkillContext) = disabledIfNoLink(ctx) ?: IDLE_STATE

    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>): CommandResult {
        val set = (args[SkillArgs.EKF_SOURCE_SET] as? Number)?.toInt()
        if (set == null || set !in 1..3) return CommandResult.Rejected(-1, SkillReason.EKF_SOURCE_SET)
        return send(ctx, VehicleCommand.SetEkfSourceSet(set))
    }
}
