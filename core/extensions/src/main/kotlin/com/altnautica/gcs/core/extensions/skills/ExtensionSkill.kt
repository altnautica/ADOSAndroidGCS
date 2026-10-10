package com.altnautica.gcs.core.extensions.skills

import com.altnautica.gcs.core.extensions.data.ExtensionFailure
import com.altnautica.gcs.core.extensions.data.ExtensionReach
import com.altnautica.gcs.core.extensions.data.WriteOutcome
import com.altnautica.gcs.core.extensions.manifest.ExtensionSkillCategory
import com.altnautica.gcs.core.extensions.manifest.SkillContribution
import com.altnautica.gcs.core.extensions.manifest.TargetActionContribution
import com.altnautica.gcs.core.model.ArmRequirement
import com.altnautica.gcs.core.model.Binding
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.model.ConfirmPolicy
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.model.SkillState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Writes one key of an extension's config on the bound node. */
fun interface ExtensionConfigWriter {
    suspend fun write(key: String, value: JsonElement): WriteOutcome
}

/** The discrete state an extension reports for one skill topic. */
data class ReportedState(val state: Kind, val badge: String? = null, val reason: String? = null) {
    enum class Kind { Active, Idle, Disabled }

    companion object {
        val Idle = ReportedState(Kind.Idle)

        /**
         * Map a published payload: an explicit `state` string wins; otherwise a follow-style
         * `{active, commanding, lock_state}` payload maps to active (badged when engaged but
         * not commanding); anything else is idle.
         */
        fun from(payload: JsonElement?): ReportedState {
            val p = payload as? JsonObject ?: return Idle
            val state = p.string("state")
            if (state == "active" || state == "idle" || state == "disabled") {
                val kind = when (state) {
                    "active" -> Kind.Active
                    "disabled" -> Kind.Disabled
                    else -> Kind.Idle
                }
                return ReportedState(kind, p.string("badge"), p.string("reason"))
            }
            val active = (p["active"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            if (active != null) {
                if (!active) return Idle
                val commanding = (p["commanding"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                if (commanding == true) return ReportedState(Kind.Active)
                val badge = when (p.string("lock_state")) {
                    "uncertain" -> "?"
                    "lost" -> "!"
                    else -> null
                }
                return ReportedState(Kind.Active, badge)
            }
            return Idle
        }

        private fun JsonObject.string(k: String): String? =
            (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** Reason shown when an extension reports itself disabled without one. */
const val REASON_EXTENSION_UNAVAILABLE = "Extension unavailable"

/** Material Symbols name for an extension's icon hint (manifests use lucide-style names). */
internal fun materialIcon(hint: String): String = when (hint.lowercase()) {
    "crosshair", "target", "locate" -> "my_location"
    "camera" -> "photo_camera"
    "video" -> "videocam"
    "thermometer" -> "thermostat"
    "palette" -> "palette"
    "circle-stop", "square" -> "stop_circle"
    "focus", "scan" -> "center_focus_strong"
    "move", "move-3d" -> "open_with"
    "rotate-ccw", "refresh-cw" -> "refresh"
    "arrow-down", "chevrons-down" -> "arrow_downward"
    "sparkles" -> "auto_awesome"
    "eye" -> "visibility"
    "zoom-in" -> "zoom_in"
    "zoom-out" -> "zoom_out"
    else -> hint.lowercase().replace('-', '_')
}

private fun ExtensionSkillCategory.toModel(): SkillCategory =
    if (this == ExtensionSkillCategory.Camera) SkillCategory.Camera else SkillCategory.Behavior

private fun armRequirementOf(wire: String?): ArmRequirement = when (wire) {
    "armed" -> ArmRequirement.Armed
    "disarmed" -> ArmRequirement.Disarmed
    else -> ArmRequirement.Any
}

/** Hold confirm every confirming extension skill uses. */
internal fun extensionConfirm(label: String): ConfirmPolicy = ConfirmPolicy(
    titleKey = label,
    messageKey = "This extension acts on the vehicle.",
    gesture = ConfirmGesture.Hold,
    holdMs = 800,
    checklistAware = false,
    danger = false,
)

internal fun WriteOutcome.toCommandResult(): CommandResult = when (this) {
    WriteOutcome.Done -> CommandResult.Accepted
    is WriteOutcome.NoPath -> CommandResult.Denied(reasonKey)
    is WriteOutcome.Failed -> when (val f = failure) {
        is ExtensionFailure.Network -> CommandResult.Timeout
        is ExtensionFailure.Refused -> CommandResult.Rejected(-1, f.message ?: "refused")
        ExtensionFailure.Unauthorized -> CommandResult.Denied("Not authorized on this node")
        ExtensionFailure.PinRequired -> CommandResult.Denied("This node needs a PIN")
        ExtensionFailure.FeatureAbsent -> CommandResult.Unsupported
    }
}

/**
 * One extension skill bound to one node: activation writes `config_key = true` (and
 * `false` to deactivate a toggle); state comes from the topic the extension publishes.
 * Instances are immutable snapshots; a new instance is emitted whenever reach or the
 * reported state changes.
 */
class ExtensionSkill(
    val node: DeviceId,
    val pluginId: String,
    val contribution: SkillContribution,
    val reach: ExtensionReach,
    val reported: ReportedState,
    private val writer: ExtensionConfigWriter,
) : Skill {
    override val id: String = skillId(pluginId, contribution.id)
    override val labelKey: String = contribution.label
    override val icon: String = materialIcon(contribution.icon)
    override val category: SkillCategory = contribution.category.toModel()
    override val source: SkillSource = SkillSource.Extension(pluginId)
    override val toggle: Boolean = contribution.toggle
    override val confirm: ConfirmPolicy? = if (contribution.confirm) extensionConfirm(contribution.label) else null
    override val armRequirement: ArmRequirement = armRequirementOf(contribution.armRequirement)
    override val cooldownMs: Long = 0
    override val defaultBinding: Binding? =
        if (contribution.defaultKey != null || contribution.defaultGamepadButton != null) {
            Binding(contribution.defaultKey, contribution.defaultGamepadButton)
        } else {
            null
        }

    override fun state(ctx: SkillContext): SkillState {
        ExtensionReach.reasonKey(reach)?.let { return SkillState(false, false, it, null, null) }
        return when (reported.state) {
            ReportedState.Kind.Active -> SkillState(true, true, null, null, null)
            ReportedState.Kind.Idle -> SkillState(true, false, null, null, null)
            ReportedState.Kind.Disabled ->
                SkillState(false, false, reported.reason ?: REASON_EXTENSION_UNAVAILABLE, null, null)
        }
    }

    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>): CommandResult =
        writer.write(contribution.configKey, JsonPrimitive(true)).toCommandResult()

    override suspend fun deactivate(ctx: SkillContext): CommandResult =
        if (toggle) writer.write(contribution.configKey, JsonPrimitive(false)).toCommandResult() else CommandResult.Unsupported

    override fun equals(other: Any?): Boolean = other is ExtensionSkill && other.id == id &&
        other.node == node && other.contribution == contribution && other.reach == reach && other.reported == reported

    override fun hashCode(): Int = listOf(id, node, contribution, reach, reported).hashCode()

    companion object {
        fun skillId(pluginId: String, localId: String) = "$pluginId:$localId"
    }
}

/** Runs a vision designate on the node before a target action writes its config. */
fun interface DesignateRunner {
    suspend fun designate(args: Map<String, Any?>): CommandResult
}

/**
 * A target action as a skill, so the cockpit's activation pipeline applies the same gates.
 * Confirm and arm requirement are inherited from the extension's skill bound to the same
 * config key; activation optionally designates the clicked target, then writes the
 * declared value.
 */
class ExtensionTargetActionSkill(
    val node: DeviceId,
    val pluginId: String,
    val action: TargetActionContribution,
    private val gatingSkill: SkillContribution?,
    val reach: ExtensionReach,
    private val writer: ExtensionConfigWriter,
    private val designator: DesignateRunner,
) : Skill {
    override val id: String = "$pluginId:target:${action.id}"
    override val labelKey: String = action.label ?: action.id
    override val icon: String = materialIcon(action.icon ?: "crosshair")
    override val category: SkillCategory = SkillCategory.Behavior
    override val source: SkillSource = SkillSource.Extension(pluginId)
    override val toggle: Boolean = false
    override val confirm: ConfirmPolicy? =
        if (gatingSkill?.confirm == true) extensionConfirm(labelKey) else null
    override val armRequirement: ArmRequirement =
        if (action.configValue == false) ArmRequirement.Any else armRequirementOf(gatingSkill?.armRequirement)
    override val cooldownMs: Long = 0
    override val defaultBinding: Binding? = action.defaultKey?.let { Binding(it, null) }

    /** Class names this action applies to (null = any). */
    val appliesToClass: String? get() = action.appliesToClass

    override fun state(ctx: SkillContext): SkillState {
        ExtensionReach.reasonKey(reach)?.let { return SkillState(false, false, it, null, null) }
        return SkillState(action.configKey != null, false, null, null, null)
    }

    override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>): CommandResult {
        if (action.designate == true) {
            val d = designator.designate(args)
            if (d != CommandResult.Accepted) return d
        }
        val key = action.configKey ?: return CommandResult.Unsupported
        val value = action.configValue ?: true
        return writer.write(key, JsonPrimitive(value)).toCommandResult()
    }

    override fun equals(other: Any?): Boolean = other is ExtensionTargetActionSkill && other.id == id &&
        other.node == node && other.action == action && other.gatingSkill == gatingSkill && other.reach == reach

    override fun hashCode(): Int = listOf(id, node, action, gatingSkill, reach).hashCode()
}
