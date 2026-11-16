package com.altnautica.gcs.feature.cockpit.alerts

import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.skills.spec.FeedbackRuleSpec
import com.altnautica.gcs.core.skills.spec.FeedbackSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Runs the cockpit spec's `feedback` rules over successive pilot states. [next] takes each
 * fresh state, or null once the state went stale or the link dropped, and returns the
 * `rumble` pattern to play for that update (the strongest class in `order` that fired), or
 * null. The first state, and the first after a loss, earns nothing.
 */
internal class PilotFeedbackRules(private val spec: FeedbackSpec) {
    private var prev: PilotState? = null

    fun next(state: PilotState?): String? {
        val before = prev
        prev = state
        if (state == null) {
            return if (before == null) null else strongest { it.on == STATE_LOST }
        }
        if (before == null) return null
        return strongest { fires(it, before, state) }
    }

    private fun strongest(fired: (FeedbackRuleSpec) -> Boolean): String? =
        spec.order.firstNotNullOfOrNull { cls -> spec.rules(cls).firstOrNull(fired)?.rumble }

    private fun fires(rule: FeedbackRuleSpec, prev: PilotState, next: PilotState): Boolean {
        val path = rule.field ?: return false
        if (rule.armedOnly && next.armed == false) return false
        val a = pilotField(prev, path)
        val b = pilotField(next, path)
        return when (rule.on) {
            "changed" -> a != b
            "new_value" -> b != JsonNull && b != a
            "entered" -> b != a && b in rule.values
            "appeared" -> a == JsonNull && b != JsonNull
            "new_item" -> newItem(rule, a, b)
            else -> false
        }
    }

    /** An item of [next] matching every `where` key whose `key` value no matching item of [prev] had. */
    private fun newItem(rule: FeedbackRuleSpec, prev: JsonElement, next: JsonElement): Boolean {
        val key = rule.key ?: return false
        fun keys(e: JsonElement): List<JsonElement?> = (e as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { item -> rule.where.all { (k, v) -> item[k] == v } }
            .map { it[key] }
        val known = keys(prev).toSet()
        return keys(next).any { it !in known }
    }

    private companion object {
        const val STATE_LOST = "state_lost"
    }
}

/**
 * The pilot-state value at a dotted snake_case wire path, as the agent sends it; JsonNull when
 * absent (the wire omits `fc_failsafe` while false) or when this app does not carry the field.
 */
internal fun pilotField(p: PilotState, path: String): JsonElement = when (path) {
    "owner" -> p.owner.wire.json()
    "profile" -> p.profile?.wire.json()
    "control_mode" -> p.controlMode?.wire.json()
    "skill.skill_id" -> p.skill?.skillId.json()
    "input_session.session" -> p.inputSession?.session?.let(::JsonPrimitive) ?: JsonNull
    "avoid_state" -> p.avoidState?.wire.json()
    "hold_reason" -> p.holdReason.json()
    "speed_cap.reason" -> p.speedCap?.reason.json()
    "link_loss_in_s" -> p.linkLossInS?.let(::JsonPrimitive) ?: JsonNull
    "rth_countdown_s" -> p.rthCountdownS?.let(::JsonPrimitive) ?: JsonNull
    "fc_failsafe" -> if (p.fcFailsafe) JsonPrimitive(true) else JsonNull
    "armed" -> p.armed?.let(::JsonPrimitive) ?: JsonNull
    "readiness" -> JsonArray(
        p.readiness.map {
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(it.id),
                    "ok" to JsonPrimitive(it.ok),
                    "severity" to JsonPrimitive(it.severity.name.lowercase()),
                    "msg" to JsonPrimitive(it.msg),
                ),
            )
        },
    )
    else -> JsonNull
}

private fun String?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
