package com.altnautica.gcs.core.skills.builtin

import com.altnautica.gcs.core.model.ArmRequirement
import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.Binding
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.model.ConfirmPolicy
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.model.SkillState
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.skills.SkillReason
import com.altnautica.gcs.core.skills.SkillTiming

/** A skill whose confirm policy depends on the activation arguments (mode changes). */
interface ArgumentConfirm {
    fun confirmFor(args: Map<String, Any?>): ConfirmPolicy?
}

/** Skill Bar traits that the shared [Skill] contract does not carry. */
interface BarTraits {
    /** False for skills that need an argument and are dispatched from their own control. */
    val bindable: Boolean

    /** Hidden from the bar when the firmware is known to lack autonomous navigation. */
    val requiresAutonomousNav: Boolean

    /** False when the vehicle is known to lack what the skill needs; the bar then hides it (CS-9 unsupported). */
    fun presentFor(ctx: SkillContext): Boolean = true
}

/** Activation argument keys understood by the built-ins. */
object SkillArgs {
    const val ALTITUDE_M = "altitudeM"
    const val TARGET_MODE = "targetMode"
    const val LAT = "lat"
    const val LON = "lon"

    /** Standard-mapping gamepad button that triggered the activation; its hold satisfies the confirm. */
    const val GAMEPAD_BUTTON = "gamepadButton"
}

object TakeoffAltitude {
    const val MIN_M = 1f
    const val MAX_M = 120f
    const val DEFAULT_M = 10f
    const val STEP_M = 1f
}

object FlyHereAltitude {
    const val MIN_M = 2f
    const val MAX_M = 120f
}

val IDLE_STATE = SkillState(enabled = true, active = false, reasonKey = null, cooldownEndsAtMs = null, charges = null)

fun disabledState(reason: String) =
    SkillState(enabled = false, active = false, reasonKey = reason, cooldownEndsAtMs = null, charges = null)

/** Builds a built-in confirm policy; title and message keys follow `skills.<key>.confirm.*`. */
fun builtinConfirm(key: String, gesture: ConfirmGesture, danger: Boolean, checklistAware: Boolean = false) =
    ConfirmPolicy(
        titleKey = "skills.$key.confirm.title",
        messageKey = "skills.$key.confirm.message",
        gesture = gesture,
        holdMs = when (gesture) {
            ConfirmGesture.Tap -> 0
            ConfirmGesture.Hold -> SkillTiming.HOLD_MS
            ConfirmGesture.Slide -> SkillTiming.SLIDE_HOLD_MS
            ConfirmGesture.Guarded -> SkillTiming.GUARDED_HOLD_MS
        },
        checklistAware = checklistAware,
        danger = danger,
    )

/** Recovery modes that stop the vehicle where it is: confirmed by the press itself. */
private val RECOVERY_MODES = setOf("LOITER", "BRAKE", "ALT_HOLD", "POSHOLD", "QLOITER", "QHOVER", "FLOWHOLD")

/** Mode names that start or continue a mission. */
private val MISSION_MODES = setOf("AUTO")

/** Confirm tier of a mode change: tap for recovery modes, slide to start a mission, hold otherwise. */
fun modeChangeConfirm(modeName: String): ConfirmPolicy? {
    val name = modeName.uppercase()
    if (name in RECOVERY_MODES) return null
    val gesture = if (name in MISSION_MODES) ConfirmGesture.Slide else ConfirmGesture.Hold
    return builtinConfirm("modeChange", gesture, danger = false)
}

/** Command skills need a fresh, commandable link to the flight controller. */
internal fun disabledIfNoLink(ctx: SkillContext): SkillState? {
    val vehicle = ctx.vehicle ?: return disabledState(SkillReason.NO_FC_LINK)
    if (!ctx.linkFresh) return disabledState(SkillReason.NO_FC_LINK)
    val reach = vehicle.reach.value
    if (reach is Reach.PhoneRadio) return disabledState(SkillReason.RECEIVE_ONLY)
    if (reach is Reach.Blocked) {
        return disabledState(if (reach.reason == BlockReason.ReceiveOnly) SkillReason.RECEIVE_ONLY else SkillReason.NO_FC_LINK)
    }
    return null
}

internal fun disabledUnlessDisarmed(ctx: SkillContext): SkillState? = when (ctx.armed) {
    true -> disabledState(SkillReason.ALREADY_ARMED)
    null -> disabledState(SkillReason.NO_FC_LINK)
    false -> null
}

internal fun disabledUnlessArmed(ctx: SkillContext, reason: String = SkillReason.NOT_ARMED): SkillState? =
    when (ctx.armed) {
        false -> disabledState(reason)
        null -> disabledState(SkillReason.NO_FC_LINK)
        true -> null
    }

internal fun ctxModes(ctx: SkillContext): List<FlightMode> =
    ctx.vehicle?.control?.availableModes?.value.orEmpty()

private suspend fun SkillContext.run(command: VehicleCommand): CommandResult =
    vehicle?.control?.run(command) ?: CommandResult.Denied(SkillReason.NO_FC_LINK)

/** Base for the built-in flight skills: one-shot, sourced from the app itself. */
abstract class BuiltinSkill(
    final override val id: String,
    final override val labelKey: String,
    final override val icon: String,
    final override val category: SkillCategory,
    final override val armRequirement: ArmRequirement,
    final override val confirm: ConfirmPolicy?,
    final override val cooldownMs: Long = 0,
    final override val defaultBinding: Binding? = null,
    final override val bindable: Boolean = true,
    final override val requiresAutonomousNav: Boolean = false,
) : Skill, BarTraits {
    final override val source: SkillSource = SkillSource.Builtin
    final override val toggle: Boolean = false

    protected suspend fun send(ctx: SkillContext, command: VehicleCommand): CommandResult = ctx.run(command)
}

object ArmSkill : BuiltinSkill(
    id = "arm", labelKey = "skills.arm", icon = "power", category = SkillCategory.Safety,
    armRequirement = ArmRequirement.Disarmed,
    confirm = builtinConfirm("arm", ConfirmGesture.Slide, danger = true, checklistAware = true),
) {
    override fun state(ctx: SkillContext) = disabledIfNoLink(ctx) ?: disabledUnlessDisarmed(ctx) ?: IDLE_STATE
    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>) = send(ctx, VehicleCommand.Arm())
}

object DisarmSkill : BuiltinSkill(
    id = "disarm", labelKey = "skills.disarm", icon = "power_off", category = SkillCategory.Safety,
    armRequirement = ArmRequirement.Armed,
    confirm = builtinConfirm("disarm", ConfirmGesture.Hold, danger = true),
) {
    override fun state(ctx: SkillContext) =
        disabledIfNoLink(ctx) ?: disabledUnlessArmed(ctx, SkillReason.ALREADY_DISARMED) ?: IDLE_STATE
    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>) = send(ctx, VehicleCommand.Disarm())
}

/** Takeoff altitude from the arguments, clamped to the allowed range; default when absent. */
fun takeoffAltitude(args: Map<String, Any?>): Float {
    val requested = (args[SkillArgs.ALTITUDE_M] as? Number)?.toFloat()
    return if (requested == null || !requested.isFinite() || requested <= 0f) {
        TakeoffAltitude.DEFAULT_M
    } else {
        requested.coerceIn(TakeoffAltitude.MIN_M, TakeoffAltitude.MAX_M)
    }
}

/**
 * Arms and climbs. On firmware with a guided mode (ArduPilot) the vehicle enters it
 * before arming: the mode it rests in after a landing or a recovery (LAND, RTL, BRAKE)
 * refuses to arm, and the takeoff command needs it anyway. Firmware without one
 * (PX4) arms where it is and switches itself on the takeoff command.
 */
object TakeoffSkill : BuiltinSkill(
    id = "takeoff", labelKey = "skills.takeoff", icon = "flight_takeoff", category = SkillCategory.Flight,
    armRequirement = ArmRequirement.Disarmed,
    confirm = builtinConfirm("takeoff", ConfirmGesture.Hold, danger = true, checklistAware = true),
    requiresAutonomousNav = true,
) {
    override fun state(ctx: SkillContext) = disabledIfNoLink(ctx) ?: disabledUnlessDisarmed(ctx) ?: IDLE_STATE
    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>): CommandResult {
        val guided = ModePreset.Guided.resolve(ctx)
        if (guided != null && !guided.name.equals(ctx.mode?.name, ignoreCase = true)) {
            val switched = send(ctx, VehicleCommand.SetMode(guided))
            if (switched != CommandResult.Accepted && switched !is CommandResult.Queued) return switched
        }
        val armed = send(ctx, VehicleCommand.Arm())
        if (armed != CommandResult.Accepted && armed !is CommandResult.Queued) return armed
        return send(ctx, VehicleCommand.Takeoff(takeoffAltitude(args)))
    }
}

object LandSkill : BuiltinSkill(
    id = "land", labelKey = "skills.land", icon = "flight_land", category = SkillCategory.Flight,
    armRequirement = ArmRequirement.Armed,
    confirm = builtinConfirm("land", ConfirmGesture.Hold, danger = true),
    requiresAutonomousNav = true,
) {
    override fun state(ctx: SkillContext) = disabledIfNoLink(ctx) ?: disabledUnlessArmed(ctx) ?: IDLE_STATE
    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>) = send(ctx, VehicleCommand.Land)
}

object RthSkill : BuiltinSkill(
    id = "rth", labelKey = "skills.rth", icon = "home", category = SkillCategory.Flight,
    armRequirement = ArmRequirement.Any,
    confirm = builtinConfirm("rth", ConfirmGesture.Hold, danger = false),
    cooldownMs = 5_000,
    requiresAutonomousNav = true,
) {
    override fun state(ctx: SkillContext) = disabledIfNoLink(ctx) ?: IDLE_STATE
    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>) = send(ctx, VehicleCommand.ReturnToLaunch)
}

object PauseSkill : BuiltinSkill(
    id = "pause", labelKey = "skills.pause", icon = "pause", category = SkillCategory.Flight,
    armRequirement = ArmRequirement.Armed,
    confirm = null,
    requiresAutonomousNav = true,
) {
    override fun state(ctx: SkillContext) = disabledIfNoLink(ctx) ?: disabledUnlessArmed(ctx) ?: IDLE_STATE
    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>): CommandResult {
        val inMission = ctx.mode?.name?.uppercase() in MISSION_MODES
        if (inMission) return send(ctx, VehicleCommand.PauseMission)
        val hold = ModePreset.Loiter.resolve(ctx) ?: return send(ctx, VehicleCommand.PauseMission)
        return send(ctx, VehicleCommand.SetMode(hold))
    }
}

object ResumeSkill : BuiltinSkill(
    id = "resume", labelKey = "skills.resume", icon = "play_arrow", category = SkillCategory.Flight,
    armRequirement = ArmRequirement.Armed,
    confirm = builtinConfirm("resume", ConfirmGesture.Hold, danger = false),
    requiresAutonomousNav = true,
) {
    override fun state(ctx: SkillContext) = disabledIfNoLink(ctx) ?: disabledUnlessArmed(ctx) ?: IDLE_STATE
    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>) = send(ctx, VehicleCommand.ResumeMission)
}

object AbortSkill : BuiltinSkill(
    id = "abort", labelKey = "skills.abort", icon = "cancel", category = SkillCategory.Safety,
    armRequirement = ArmRequirement.Any,
    confirm = builtinConfirm("abort", ConfirmGesture.Hold, danger = true),
) {
    override fun state(ctx: SkillContext) = disabledIfNoLink(ctx) ?: IDLE_STATE
    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>) = send(ctx, VehicleCommand.Land)
}

object KillSkill : BuiltinSkill(
    id = "kill", labelKey = "skills.kill", icon = "dangerous", category = SkillCategory.Safety,
    armRequirement = ArmRequirement.Any,
    confirm = builtinConfirm("kill", ConfirmGesture.Guarded, danger = true),
) {
    override fun state(ctx: SkillContext) = disabledIfNoLink(ctx) ?: IDLE_STATE
    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>) = send(ctx, VehicleCommand.Kill)
}

/**
 * Mode presets. A preset names a behaviour, not a firmware mode: each resolves, per
 * [VehicleClass], to the first listed mode the vehicle advertises (a plane's Loiter is
 * LOITER, a hovering VTOL's is QLOITER). A preset with no mode for the vehicle is hidden.
 * [tierMode] fixes the confirm tier (CS-6): Loiter, Brake and Alt hold are tap.
 */
enum class ModePreset(val skillId: String, val labelKey: String, val icon: String, val tierMode: String) {
    Loiter("mode.loiter", "skills.modeLoiter", "my_location", "LOITER"),
    Brake("mode.brake", "skills.modeBrake", "front_hand", "BRAKE"),
    AltHold("mode.althold", "skills.modeAltHold", "height", "ALT_HOLD"),
    Stabilize("mode.stabilize", "skills.modeStabilize", "center_focus_strong", "STABILIZE"),
    Guided("mode.guided", "skills.modeGuided", "navigation", "GUIDED"),
    Auto("mode.auto", "skills.modeAuto", "route", "AUTO"),
    ;

    /** Mode names this preset means on [cls], best first; empty when the class has no such mode. */
    fun candidates(cls: VehicleClass): List<String> = when (this) {
        Loiter -> when (cls) {
            VehicleClass.Multirotor -> listOf("LOITER", "POSHOLD")
            VehicleClass.FixedWing -> listOf("LOITER")
            VehicleClass.Vtol -> listOf("QLOITER", "LOITER")
            VehicleClass.Rover -> listOf("LOITER", "HOLD")
            VehicleClass.Sub -> listOf("POSHOLD")
            VehicleClass.Unknown -> listOf("LOITER", "POSHOLD")
        }
        Brake -> when (cls) {
            VehicleClass.Multirotor, VehicleClass.Unknown -> listOf("BRAKE")
            else -> emptyList()
        }
        AltHold -> when (cls) {
            VehicleClass.Multirotor, VehicleClass.Sub, VehicleClass.Unknown -> listOf("ALT_HOLD")
            VehicleClass.FixedWing -> listOf("FBWB")
            VehicleClass.Vtol -> listOf("QHOVER", "ALT_HOLD")
            VehicleClass.Rover -> emptyList()
        }
        Stabilize -> when (cls) {
            VehicleClass.Vtol -> listOf("QSTABILIZE", "STABILIZE")
            VehicleClass.Rover -> listOf("MANUAL")
            else -> listOf("STABILIZE")
        }
        Guided -> listOf("GUIDED")
        Auto -> listOf("AUTO")
    }

    /** The advertised mode this preset means on [cls], or null when the vehicle has none. */
    fun resolve(available: List<FlightMode>, cls: VehicleClass): FlightMode? =
        candidates(cls).firstNotNullOfOrNull { wanted -> available.firstOrNull { it.name.equals(wanted, ignoreCase = true) } }

    fun resolve(ctx: SkillContext): FlightMode? = resolve(ctxModes(ctx), ctx.vehicleClass())
}

class ModeSkill(val preset: ModePreset) : BuiltinSkill(
    id = preset.skillId, labelKey = preset.labelKey, icon = preset.icon, category = SkillCategory.Flight,
    armRequirement = ArmRequirement.Any,
    confirm = modeChangeConfirm(preset.tierMode),
), ArgumentConfirm {
    override fun confirmFor(args: Map<String, Any?>): ConfirmPolicy? = modeChangeConfirm(preset.tierMode)

    /** Shown while the mode list is still unknown; hidden once it is known to lack the preset. */
    override fun presentFor(ctx: SkillContext): Boolean = ctxModes(ctx).isEmpty() || preset.resolve(ctx) != null

    override fun state(ctx: SkillContext): SkillState {
        disabledIfNoLink(ctx)?.let { return it }
        if (preset.resolve(ctx) == null) return disabledState(SkillReason.MODE_UNAVAILABLE)
        return IDLE_STATE
    }

    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>): CommandResult {
        val mode = preset.resolve(ctx) ?: return CommandResult.Rejected(-1, SkillReason.MODE_UNAVAILABLE)
        return send(ctx, VehicleCommand.SetMode(mode))
    }
}

/** Sets an arbitrary mode named by [SkillArgs.TARGET_MODE]; driven from the mode picker. */
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
        return send(ctx, VehicleCommand.SetMode(mode))
    }
}

/** Flies to a map point at a relative altitude; dispatched from the map's own control. */
object FlyHereSkill : BuiltinSkill(
    id = "fly-here", labelKey = "skills.flyHere", icon = "near_me", category = SkillCategory.Flight,
    armRequirement = ArmRequirement.Armed,
    confirm = builtinConfirm("flyHere", ConfirmGesture.Hold, danger = false),
    bindable = false,
    requiresAutonomousNav = true,
) {
    override fun state(ctx: SkillContext) = disabledIfNoLink(ctx) ?: disabledUnlessArmed(ctx) ?: IDLE_STATE

    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>): CommandResult {
        val lat = (args[SkillArgs.LAT] as? Number)?.toDouble()
        val lon = (args[SkillArgs.LON] as? Number)?.toDouble()
        val alt = (args[SkillArgs.ALTITUDE_M] as? Number)?.toFloat()
        if (lat == null || lon == null || alt == null || !lat.isFinite() || !lon.isFinite() ||
            alt < FlyHereAltitude.MIN_M || alt > FlyHereAltitude.MAX_M
        ) {
            return CommandResult.Rejected(-1, SkillReason.FLY_HERE_TARGET)
        }
        return send(ctx, VehicleCommand.FlyTo(lat, lon, alt))
    }
}

/**
 * Every built-in skill in registration order. Held in its own object so its
 * initialisation never runs inside a skill object's initialiser (a cycle that
 * would capture a not-yet-created skill as null).
 */
object BuiltinSkills {
    val all: List<Skill> by lazy {
        listOf(ArmSkill, DisarmSkill, TakeoffSkill, LandSkill, RthSkill, PauseSkill, ResumeSkill, AbortSkill, KillSkill) +
            ModePreset.entries.map(::ModeSkill) + listOf(SetModeSkill, FlyHereSkill)
    }
}
