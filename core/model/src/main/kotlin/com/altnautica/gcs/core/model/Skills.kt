package com.altnautica.gcs.core.model

import kotlinx.coroutines.flow.Flow

enum class ConfirmGesture { Tap, Hold, Slide, Guarded }

data class ConfirmPolicy(
    val titleKey: String,
    val messageKey: String,
    val gesture: ConfirmGesture,
    val holdMs: Int,
    val checklistAware: Boolean,
    val danger: Boolean,
)

/**
 * Where a skill sits. Built-ins are [Flight] or [Safety]; extension skills declare
 * [Behavior], [Camera], [Navigation] or [Utility], the skill picker's tabs.
 */
enum class SkillCategory { Flight, Behavior, Camera, Navigation, Utility, Safety }

sealed interface SkillSource {
    data object Builtin : SkillSource

    /** A skill contributed by an extension; [pluginName] is its display name. */
    data class Extension(val pluginId: String, val pluginName: String = pluginId) : SkillSource
}

enum class ArmRequirement { Any, Armed, Disarmed }

/**
 * A skill's state for the current vehicle.
 *
 * - [badge]: short text the extension reports beside the skill (a count, a mode).
 * - [pending]: a write is awaiting its state echo; the surface shows a pending ring.
 * - [lastSeenS]: the extension's state is older than the freshness window; the surface
 *   dims the skill and shows "last seen [lastSeenS] s".
 * - [stale]: the link is lost while the skill is active; it reads ACTIVE (stale) and
 *   Stop stays enabled.
 * - [stopQueued]: a Stop pressed while the link was lost waits to be sent.
 */
data class SkillState(
    val enabled: Boolean,
    val active: Boolean,
    val reasonKey: String?,
    val cooldownEndsAtMs: Long?,
    val charges: Int?,
    val badge: String? = null,
    val pending: Boolean = false,
    val lastSeenS: Int? = null,
    val stale: Boolean = false,
    val stopQueued: Boolean = false,
)

/**
 * Everything a skill reads to compute its state and act. [pilot] is the onboard pilot's
 * latest state and [pilotLink] its live channel; both are null without ADOS Pilot.
 */
data class SkillContext(
    val vehicle: Vehicle?,
    val armed: Boolean?,
    val mode: FlightMode?,
    val linkFresh: Boolean,
    val checklistReady: Boolean,
    val autonomousNav: Boolean?,
    val pilot: PilotState? = null,
    val pilotLink: PilotLink? = null,
)

data class Binding(
    val keyChord: String?,
    val gamepadButton: Int?,
)

interface Skill {
    val id: String
    val labelKey: String
    val icon: String
    val category: SkillCategory
    val source: SkillSource
    val toggle: Boolean
    val confirm: ConfirmPolicy?
    val armRequirement: ArmRequirement
    val cooldownMs: Long
    val defaultBinding: Binding?

    /** The skill commands the vehicle: it holds the motion lease and built-in recoveries stop it. */
    val motion: Boolean get() = false

    /** Parameter keys shown in the skill's Tweak panel. */
    val params: List<String> get() = emptyList()

    /** The skill consumes stick nudges while active. */
    val nudge: Boolean get() = false

    /** Fires once, with no active state (camera shots, for example). */
    val oneShot: Boolean get() = false

    /** Airframes the skill flies on; a surface hides it for any other vehicle class. */
    val vehicleClasses: Set<PilotVehicleClass> get() = PilotVehicleClass.entries.toSet()

    fun state(ctx: SkillContext): SkillState

    suspend fun activate(ctx: SkillContext, args: Map<String, Any?>): CommandResult

    suspend fun deactivate(ctx: SkillContext): CommandResult = CommandResult.Unsupported
}

/** Adds skills to the registry (extensions, for example); bound into a Hilt set. */
interface SkillContributor {
    val skills: Flow<List<Skill>>
}
