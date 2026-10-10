package com.altnautica.gcs.core.extensions.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Method → capability table for the extension bridge. A method missing from the table is
 * rejected; a null capability means always allowed.
 */
object MethodRules {
    /** Reserved `command.send` name an extension uses to write its own config. */
    const val PLUGIN_CONFIG_WRITE = "plugin.config.write"

    /** Reserved `command.send` name that retargets the vision tracker. */
    const val VISION_DESIGNATE = "vision.designate"

    private class Rule(
        val capability: String?,
        val requireTopic: Boolean = false,
        val resolve: ((JsonElement?) -> String?)? = null,
    )

    private val RULES: Map<String, Rule> = mapOf(
        "ping" to Rule(null),
        "notify" to Rule("ui.slot.notification-channel"),
        "notification.publish" to Rule("ui.slot.notification-channel"),
        "i18n.t" to Rule(null),
        "telemetry.subscribe" to Rule("telemetry.subscribe", requireTopic = true) { args ->
            topicOf(args)?.let { "telemetry.subscribe.$it" }
        },
        "telemetry.unsubscribe" to Rule(null),
        "command.send" to Rule("command.send") { args ->
            if (stringArg(args, "command") == VISION_DESIGNATE) "vision.track.designate" else "command.send"
        },
        "recording.start" to Rule("recording.write"),
        "recording.stop" to Rule("recording.write"),
        "recording.mark" to Rule("recording.write"),
        "mission.read" to Rule("mission.read"),
        "mission.write" to Rule("mission.write"),
        "events.subscribe" to Rule("event.subscribe", requireTopic = true),
        "events.publish" to Rule("event.publish", requireTopic = true),
        "events.unsubscribe" to Rule(null, requireTopic = true),
        "cloud.read" to Rule("cloud.read"),
        "records.list" to Rule("cloud.records"),
        "records.get" to Rule("cloud.records"),
        "records.put" to Rule("cloud.records"),
        "records.remove" to Rule("cloud.records"),
        "cockpit.marks" to Rule("ui.slot.video-overlay"),
        "cockpit.marks.clear" to Rule(null),
        "perception.read" to Rule("perception.read"),
        "perception.subscribe" to Rule("perception.subscribe"),
        "perception.unsubscribe" to Rule(null),
        "perception.health" to Rule("perception.read"),
    )

    /** Outcome of resolving the capability a call needs. */
    sealed interface Required {
        data object Unknown : Required
        data object Always : Required
        data class Capability(val id: String) : Required
    }

    fun required(method: String, args: JsonElement?): Required {
        val rule = RULES[method] ?: return Required.Unknown
        if (rule.requireTopic && topicOf(args) == null) return Required.Unknown
        val cap = rule.capability ?: return Required.Always
        val resolved = rule.resolve?.invoke(args) ?: if (rule.resolve != null) return Required.Unknown else cap
        return Required.Capability(resolved)
    }

    fun isKnown(method: String): Boolean = method in RULES

    /** Whether [granted] holds exactly [capability] (per-topic telemetry ids included). */
    fun isGranted(capability: String, granted: Set<String>): Boolean = capability in granted

    internal fun topicOf(args: JsonElement?): String? = stringArg(args, "topic")

    internal fun stringArg(args: JsonElement?, key: String): String? {
        val p = (args as? JsonObject)?.get(key) as? JsonPrimitive ?: return null
        return if (p.isString) p.content else null
    }
}
