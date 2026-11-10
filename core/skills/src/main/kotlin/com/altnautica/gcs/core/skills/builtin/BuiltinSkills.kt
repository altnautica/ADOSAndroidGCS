package com.altnautica.gcs.core.skills.builtin

import com.altnautica.gcs.core.model.ArmRequirement
import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.Binding
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.model.ConfirmPolicy
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotActionResult
import com.altnautica.gcs.core.model.PilotLink
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.model.SkillState
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.skills.SkillReason
import com.altnautica.gcs.core.skills.spec.BuiltinSpec
import com.altnautica.gcs.core.skills.spec.BuiltinsSpec
import com.altnautica.gcs.core.skills.spec.Ds4Spec
import com.altnautica.gcs.core.skills.spec.SpecArmRequirement
import com.altnautica.gcs.core.skills.spec.SpecConfirmKind
import com.altnautica.gcs.core.skills.spec.SpecStaleLink
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A skill whose confirm policy depends on the activation arguments (mode changes). */
interface ArgumentConfirm {
    fun confirmFor(args: Map<String, Any?>): ConfirmPolicy?
}

/** Traits of a built-in that the shared [Skill] contract does not carry. */
interface BuiltinTraits {
    /** False for skills that need an argument and are dispatched from their own control. */
    val bindable: Boolean

    /** Sent even while the link is stale (pause, kill): never disabled for freshness. */
    val sendsBlind: Boolean
}

/** Activation argument keys understood by the built-ins. */
object SkillArgs {
    const val ALTITUDE_M = "altitudeM"
    const val TARGET_MODE = "targetMode"
    const val LAT = "lat"
    const val LON = "lon"

    /** EKF3 source set (1-3) for [EkfSourceSetSkill]. */
    const val EKF_SOURCE_SET = "ekfSourceSet"

    /** Standard-mapping gamepad button that triggered the activation; its hold satisfies the confirm. */
    const val GAMEPAD_BUTTON = "gamepadButton"
}

/** Ids of the built-in flight actions; the table itself is the cockpit spec's `builtins.json`. */
object BuiltinIds {
    const val ARM = "arm"
    const val DISARM = "disarm"
    const val TAKEOFF = "takeoff"
    const val LAND = "land"
    const val RTH = "rth"
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val TAKE_CONTROL = "take_control"
    const val KILL = "kill"

    /** Built-ins that stop a running motion skill before they act. */
    val PREEMPTING: Set<String> = setOf(PAUSE, LAND, RTH, KILL)
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

/** A confirm policy whose title and message keys follow `skills.<key>.confirm.*`. */
fun builtinConfirm(key: String, gesture: ConfirmGesture, holdMs: Int, danger: Boolean, checklistAware: Boolean = false) =
    ConfirmPolicy(
        titleKey = "skills.$key.confirm.title",
        messageKey = "skills.$key.confirm.message",
        gesture = gesture,
        holdMs = holdMs,
        checklistAware = checklistAware,
        danger = danger,
    )

/** Recovery modes that stop the vehicle where it is: confirmed by the press itself. */
private val RECOVERY_MODES = setOf("LOITER", "BRAKE", "ALT_HOLD", "POSHOLD", "QLOITER", "QHOVER", "FLOWHOLD")

/** Hold of a non-recovery mode change from the Mode sheet. */
private const val MODE_CHANGE_HOLD_MS = 1_500

/** Confirm tier of a mode change from the Mode sheet: tap for recovery modes, a hold otherwise. */
fun modeChangeConfirm(modeName: String): ConfirmPolicy? {
    if (modeName.uppercase() in RECOVERY_MODES) return null
    return builtinConfirm("modeChange", ConfirmGesture.Hold, MODE_CHANGE_HOLD_MS, danger = false)
}

/** True when actions go to the onboard pilot rather than straight to the flight controller. */
fun SkillContext.routesToPilot(): Boolean = pilotLink != null && pilot?.controlMode == ControlMode.Ados

/** Built-ins that must not depend on a pilot that stopped answering. */
internal val RECOVERY_BUILTINS = setOf(BuiltinIds.PAUSE, BuiltinIds.RTH, BuiltinIds.LAND, BuiltinIds.KILL)

/**
 * Where built-in [id] goes. In ADOS Pilot control it goes to the onboard pilot; a
 * recovery action (pause, return, land, kill) goes to the pilot only while its state is
 * fresh. A channel kept up by keepalives alone, or a stale one, sends it to the flight
 * controller's command path instead, so it never waits on a pilot that stopped answering.
 */
internal fun SkillContext.builtinRoutesToPilot(id: String): Boolean {
    if (!routesToPilot()) return false
    return id !in RECOVERY_BUILTINS || pilotLink?.stateFresh == true
}

/** The vehicle can be commanded at all: present and not receive-only. */
private fun disabledIfNoCommandPath(ctx: SkillContext): SkillState? {
    val vehicle = ctx.vehicle ?: return disabledState(SkillReason.NO_FC_LINK)
    val reach = vehicle.reach.value
    if (reach is Reach.PhoneRadio) return disabledState(SkillReason.RECEIVE_ONLY)
    if (reach is Reach.Blocked) {
        return disabledState(if (reach.reason == BlockReason.ReceiveOnly) SkillReason.RECEIVE_ONLY else SkillReason.NO_FC_LINK)
    }
    return null
}

/**
 * Command skills need a fresh, commandable link: the pilot channel when the action goes
 * to the pilot ([toPilot]), the FC link otherwise.
 */
internal fun disabledIfNoLink(ctx: SkillContext, toPilot: Boolean = ctx.routesToPilot()): SkillState? {
    disabledIfNoCommandPath(ctx)?.let { return it }
    val fresh = if (toPilot) ctx.pilotLink?.connected?.value == true else ctx.linkFresh
    return if (fresh) null else disabledState(SkillReason.NO_FC_LINK)
}

internal fun ctxModes(ctx: SkillContext): List<FlightMode> =
    ctx.vehicle?.control?.availableModes?.value.orEmpty()

private suspend fun SkillContext.run(command: VehicleCommand): CommandResult =
    vehicle?.control?.run(command) ?: CommandResult.Denied(SkillReason.NO_FC_LINK)

/** Sends [action] to the pilot and reports its answer: a refusal carries the pilot's reason. */
private suspend fun SkillContext.sendPilot(action: PilotAction): CommandResult =
    when (val r = pilotLink?.action(action) ?: PilotActionResult.Unavailable) {
        PilotActionResult.Accepted -> CommandResult.Accepted
        is PilotActionResult.Refused -> CommandResult.Rejected(-1, r.reason.orEmpty())
        PilotActionResult.NoAnswer -> CommandResult.Timeout
        PilotActionResult.Unavailable -> CommandResult.Denied(SkillReason.PILOT_UNAVAILABLE)
    }

/** Base for the built-ins: one-shot, sourced from the app itself. */
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
    final override val sendsBlind: Boolean = false,
) : Skill, BuiltinTraits {
    final override val source: SkillSource = SkillSource.Builtin
    final override val toggle: Boolean = false

    /**
     * Sends [command] to the flight controller. With a pilot link, a flight command becomes the
     * newest command on that link: an older recovery press still waiting on the pilot never runs
     * after it. Configuration commands (EKF source set, gimbal, camera, motor test, reboot) leave
     * a pending recovery press alone.
     */
    protected suspend fun send(ctx: SkillContext, command: VehicleCommand): CommandResult {
        val link = ctx.pilotLink
        if (link != null && CommandOrder.supersedes(command)) CommandOrder.supersede(link) { ctx.run(command) }
        return ctx.run(command)
    }
}

/**
 * Per pilot link, the order of the flight commands this surface sent: a count, and how to send
 * the newest again. A recovery press waiting on the pilot compares the count to tell a newer
 * command went out; if the pilot then accepts the older action late, the newest goes out again
 * on its own route, so the vehicle ends on it.
 */
internal object CommandOrder {
    private class Order {
        val generation = AtomicLong()
        @Volatile var newest: (suspend () -> Unit)? = null
    }

    private val orders = WeakHashMap<PilotLink, Order>()

    private fun order(link: PilotLink): Order = synchronized(orders) { orders.getOrPut(link) { Order() } }

    /**
     * The explicit allowlist of flight-affecting commands that supersede a pending recovery
     * press: mode changes, arm/disarm, takeoff, reposition, and the recovery commands. Any
     * other command, including every one added later, never does.
     */
    fun supersedes(command: VehicleCommand): Boolean = when (command) {
        is VehicleCommand.SetMode, is VehicleCommand.Arm, is VehicleCommand.Disarm, is VehicleCommand.Takeoff,
        is VehicleCommand.FlyTo, VehicleCommand.Land, VehicleCommand.ReturnToLaunch, VehicleCommand.PauseMission,
        VehicleCommand.ResumeMission, VehicleCommand.Kill -> true
        is VehicleCommand.SetEkfSourceSet, is VehicleCommand.MotorTest, VehicleCommand.RebootAutopilot,
        is VehicleCommand.GimbalRate, VehicleCommand.GimbalRecenter, is VehicleCommand.CameraZoom,
        VehicleCommand.CapturePhoto -> false
    }

    /** Marks a newer command on [link]; [resend] sends it again. Returns its generation. */
    fun supersede(link: PilotLink, resend: suspend () -> Unit): Long {
        val o = order(link)
        o.newest = resend
        return o.generation.incrementAndGet()
    }

    fun current(link: PilotLink): Long = order(link).generation.get()

    /** Sends the newest command on [link] again. */
    suspend fun resendNewest(link: PilotLink) {
        order(link).newest?.invoke()
    }
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

/** Firmware modes that hold position, best first, for the vehicle class. */
private fun holdCandidates(cls: VehicleClass): List<String> = when (cls) {
    VehicleClass.Multirotor, VehicleClass.Unknown -> listOf("BRAKE", "LOITER", "POSHOLD")
    VehicleClass.FixedWing -> listOf("LOITER")
    VehicleClass.Vtol -> listOf("QLOITER", "QHOVER", "LOITER")
    VehicleClass.Rover -> listOf("HOLD", "LOITER")
    VehicleClass.Sub -> listOf("POSHOLD")
}

/** The advertised mode that holds this vehicle where it is, or null when it has none. */
internal fun holdMode(ctx: SkillContext): FlightMode? {
    val modes = ctxModes(ctx)
    return holdCandidates(ctx.vehicleClass()).firstNotNullOfOrNull { wanted ->
        modes.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
    }
}

private fun armRequirementOf(spec: SpecArmRequirement): ArmRequirement = when (spec) {
    SpecArmRequirement.Any, SpecArmRequirement.DisarmedOrLanded -> ArmRequirement.Any
    SpecArmRequirement.Armed, SpecArmRequirement.ArmedLanded -> ArmRequirement.Armed
    SpecArmRequirement.Disarmed -> ArmRequirement.Disarmed
}

private val DANGER = setOf(BuiltinIds.ARM, BuiltinIds.DISARM, BuiltinIds.TAKEOFF, BuiltinIds.LAND, BuiltinIds.KILL)
private val CHECKLIST_AWARE = setOf(BuiltinIds.ARM, BuiltinIds.TAKEOFF)

private fun confirmOf(spec: BuiltinSpec): ConfirmPolicy? {
    val gesture = when (spec.confirm.kind) {
        SpecConfirmKind.Tap -> return null
        SpecConfirmKind.Hold -> ConfirmGesture.Hold
        SpecConfirmKind.HoldConfirm -> ConfirmGesture.Guarded
    }
    return builtinConfirm(spec.id, gesture, spec.confirm.ms, danger = spec.id in DANGER, checklistAware = spec.id in CHECKLIST_AWARE)
}

private fun categoryOf(id: String): SkillCategory = when (id) {
    BuiltinIds.ARM, BuiltinIds.DISARM, BuiltinIds.KILL -> SkillCategory.Safety
    else -> SkillCategory.Flight
}

/**
 * One row of the built-in table. Its label, arm requirement, confirm tier, link
 * freshness and keyboard chord come from the spec row; what it does is decided by its
 * id. In ADOS Pilot control the action goes to the onboard pilot by name; otherwise
 * the equivalent command goes to the flight controller.
 */
class TableBuiltinSkill(val spec: BuiltinSpec) : BuiltinSkill(
    id = spec.id,
    labelKey = spec.label,
    icon = spec.icon,
    category = categoryOf(spec.id),
    armRequirement = armRequirementOf(spec.armRequirement),
    confirm = confirmOf(spec),
    defaultBinding = spec.keyboard?.let { Binding(it, null) },
    sendsBlind = spec.staleLink == SpecStaleLink.SendBlind,
) {
    override fun state(ctx: SkillContext): SkillState {
        val link = if (sendsBlind) disabledIfNoCommandPath(ctx) else disabledIfNoLink(ctx, ctx.builtinRoutesToPilot(spec.id))
        link?.let { return it }
        if (spec.id == BuiltinIds.TAKE_CONTROL && ctx.pilotLink == null) return disabledState(SkillReason.PILOT_UNAVAILABLE)
        armGate(ctx)?.let { return it }
        if (spec.id == BuiltinIds.RESUME && !ctx.routesToPilot()) {
            val mission = ctx.pilot?.mission?.state
            if (mission != null && mission != MissionState.Paused) return disabledState(SkillReason.NO_PAUSED_MISSION)
        }
        return IDLE_STATE
    }

    /** The table's arm requirement; landed comes from the pilot and is unknown without it. */
    private fun armGate(ctx: SkillContext): SkillState? {
        val armed = ctx.armed
        val landed = ctx.pilot?.landed
        return when (spec.armRequirement) {
            SpecArmRequirement.Any -> null
            SpecArmRequirement.Armed -> when (armed) {
                false -> disabledState(SkillReason.NOT_ARMED)
                null -> if (sendsBlind) null else disabledState(SkillReason.NO_FC_LINK)
                true -> null
            }
            SpecArmRequirement.Disarmed -> when (armed) {
                true -> disabledState(SkillReason.ALREADY_ARMED)
                null -> disabledState(SkillReason.NO_FC_LINK)
                false -> null
            }
            SpecArmRequirement.ArmedLanded -> when {
                armed == null -> disabledState(SkillReason.NO_FC_LINK)
                !armed -> disabledState(SkillReason.ALREADY_DISARMED)
                landed == false -> disabledState(SkillReason.NOT_LANDED)
                else -> null
            }
            SpecArmRequirement.DisarmedOrLanded -> when {
                armed == null -> disabledState(SkillReason.NO_FC_LINK)
                armed && landed != true -> disabledState(SkillReason.ALREADY_ARMED)
                else -> null
            }
        }
    }

    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>): CommandResult {
        if (spec.id == BuiltinIds.TAKE_CONTROL) return ctx.sendPilot(PilotAction(spec.pilotAction))
        val recovery = spec.id in RECOVERY_BUILTINS
        val link = ctx.pilotLink
        // A second recovery press while the first still waits on the pilot goes to the FC at once.
        val escalate = recovery && link != null && link in pendingRecovery
        if (!escalate && ctx.builtinRoutesToPilot(spec.id)) {
            val pilotArgs = if (spec.id == BuiltinIds.TAKEOFF) mapOf(PILOT_ARG_ALT_M to takeoffAltitude(args)) else emptyMap()
            if (!recovery || link == null) return ctx.sendPilot(PilotAction(spec.pilotAction, pilotArgs))
            val action = PilotAction(spec.pilotAction, pilotArgs)
            // Every recovery press supersedes the commands before it on this link: an older press
            // still waiting on the pilot never runs after a newer one went out.
            val generation = CommandOrder.supersede(link) { link.action(action) }
            pendingRecovery.add(link)
            val answer = try {
                link.action(action)
            } finally {
                pendingRecovery.remove(link)
            }
            when (answer) {
                // Accepted late, after a newer command went out: the newest goes out again so it is the last.
                PilotActionResult.Accepted -> return if (CommandOrder.current(link) == generation) {
                    CommandResult.Accepted
                } else {
                    CommandOrder.resendNewest(link)
                    CommandResult.Denied(SkillReason.SUPERSEDED)
                }
                is PilotActionResult.Refused -> return CommandResult.Rejected(-1, answer.reason.orEmpty())
                // The pilot did not answer: the flight controller takes the action when it can,
                // unless a newer recovery command went out meanwhile.
                PilotActionResult.NoAnswer, PilotActionResult.Unavailable -> when {
                    CommandOrder.current(link) != generation -> return CommandResult.Denied(SkillReason.SUPERSEDED)
                    !ctx.linkFresh -> return if (answer == PilotActionResult.NoAnswer) CommandResult.Timeout else CommandResult.Denied(SkillReason.PILOT_UNAVAILABLE)
                }
            }
        }
        return when (spec.id) {
            BuiltinIds.ARM -> send(ctx, VehicleCommand.Arm())
            BuiltinIds.DISARM -> send(ctx, VehicleCommand.Disarm())
            BuiltinIds.TAKEOFF -> takeoff(ctx, args)
            BuiltinIds.LAND -> send(ctx, VehicleCommand.Land)
            BuiltinIds.RTH -> send(ctx, VehicleCommand.ReturnToLaunch)
            BuiltinIds.PAUSE -> pause(ctx)
            BuiltinIds.RESUME -> send(ctx, VehicleCommand.ResumeMission)
            BuiltinIds.KILL -> send(ctx, VehicleCommand.Kill)
            else -> CommandResult.Unsupported
        }
    }

    /**
     * Arms and climbs. On firmware with a guided mode (ArduPilot) the vehicle enters it
     * before arming: the mode it rests in after a landing or a recovery (LAND, RTL, BRAKE)
     * refuses to arm, and the takeoff command needs it anyway. Firmware without one (PX4)
     * arms where it is and switches itself on the takeoff command.
     */
    private suspend fun takeoff(ctx: SkillContext, args: Map<String, Any?>): CommandResult {
        val guided = ctxModes(ctx).firstOrNull { it.name.equals(GUIDED, ignoreCase = true) }
        if (guided != null && !guided.name.equals(ctx.mode?.name, ignoreCase = true)) {
            val switched = send(ctx, VehicleCommand.SetMode(guided))
            if (switched != CommandResult.Accepted && switched !is CommandResult.Queued) return switched
        }
        if (ctx.armed != true) {
            val armed = send(ctx, VehicleCommand.Arm())
            if (armed != CommandResult.Accepted && armed !is CommandResult.Queued) return armed
        }
        return send(ctx, VehicleCommand.Takeoff(takeoffAltitude(args)))
    }

    /** A running mission pauses in place; anything else brakes into the vehicle's hold mode. */
    private suspend fun pause(ctx: SkillContext): CommandResult {
        if (ctx.pilot?.mission?.state == MissionState.Active) return send(ctx, VehicleCommand.PauseMission)
        val hold = holdMode(ctx) ?: return send(ctx, VehicleCommand.PauseMission)
        return send(ctx, VehicleCommand.SetMode(hold))
    }

    private companion object {
        const val GUIDED = "GUIDED"
        const val PILOT_ARG_ALT_M = "alt_m"

        /** Pilot links with a recovery action still waiting on the pilot's answer. */
        val pendingRecovery: MutableSet<PilotLink> = ConcurrentHashMap.newKeySet()
    }
}

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
        return send(ctx, VehicleCommand.SetMode(mode))
    }
}

/**
 * True when a map point goes to the onboard pilot's `goto`: ADOS Pilot control and the pilot
 * offers `goto` to this surface. The goto takes over from any running mission or skill, as the
 * hold confirm says. Under FC manual or ELRS control, or with no `goto` offered, the point
 * takes the flight controller's route.
 */
internal fun SkillContext.flyHereToPilot(): Boolean {
    val p = pilot ?: return false
    return routesToPilot() && PilotAction.GOTO in p.surfaceOps
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
 * a hold confirm. The pilot's `goto` flies it when [flyHereToPilot], the FC's guided goto otherwise.
 */
object FlyHereSkill : BuiltinSkill(
    id = "fly-here", labelKey = "skills.flyHere", icon = "near_me", category = SkillCategory.Flight,
    armRequirement = ArmRequirement.Armed,
    confirm = builtinConfirm("flyHere", ConfirmGesture.Hold, holdMs = 800, danger = false),
    bindable = false,
) {
    override fun state(ctx: SkillContext): SkillState {
        disabledIfNoLink(ctx, ctx.flyHereToPilot())?.let { return it }
        return when (ctx.armed) {
            false -> disabledState(SkillReason.NOT_ARMED)
            null -> disabledState(SkillReason.NO_FC_LINK)
            true -> IDLE_STATE
        }
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
        if (ctx.flyHereToPilot()) {
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

/** Every built-in skill, in table order. */
object BuiltinSkills {
    /**
     * The built-in table rows, then a profile skill per F-key of the table's
     * `profile_keys` (labelled from the controller action list), then the built-ins
     * dispatched from their own controls.
     */
    fun from(builtins: BuiltinsSpec, ds4: Ds4Spec): List<Skill> {
        val labels = ds4.actions.associate { it.id to it.label }
        val profiles = builtins.profileKeys.entries.sortedBy { it.key }.mapNotNull { (_, action) ->
            val profile = PilotProfile.entries.firstOrNull { PilotAction.profile(it).name == action } ?: return@mapNotNull null
            ProfileSkill(profile, labels[action] ?: action)
        }
        return builtins.builtins.map(::TableBuiltinSkill) + profiles + listOf(SetModeSkill, FlyHereSkill, EkfSourceSetSkill)
    }
}
