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

enum class SkillCategory { Flight, Behavior, Camera, Safety }

sealed interface SkillSource {
    data object Builtin : SkillSource
    data class Extension(val pluginId: String) : SkillSource
}

enum class ArmRequirement { Any, Armed, Disarmed }

data class SkillState(
    val enabled: Boolean,
    val active: Boolean,
    val reasonKey: String?,
    val cooldownEndsAtMs: Long?,
    val charges: Int?,
)

data class SkillContext(
    val vehicle: Vehicle?,
    val armed: Boolean?,
    val mode: FlightMode?,
    val linkFresh: Boolean,
    val checklistReady: Boolean,
    val autonomousNav: Boolean?,
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

    fun state(ctx: SkillContext): SkillState

    suspend fun activate(ctx: SkillContext, args: Map<String, Any?>): CommandResult

    suspend fun deactivate(ctx: SkillContext): CommandResult = CommandResult.Unsupported
}

/** Adds skills to the registry (extensions, for example); bound into a Hilt set. */
interface SkillContributor {
    val skills: Flow<List<Skill>>
}
