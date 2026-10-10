package com.altnautica.gcs.core.extensions.bridge

import com.altnautica.gcs.core.model.DeviceId
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory publish/subscribe bus shared by every mounted extension. Topics match exactly,
 * by `prefix.*`, or `*`. Each event carries its origin: an extension id, or an agent-state
 * origin for state an extension's agent half published on one node.
 */
@Singleton
class ExtensionEventBus @Inject constructor() {

    fun interface Listener {
        fun onEvent(topic: String, payload: JsonElement?, origin: String)
    }

    private class Sub(val exact: String?, val prefix: String?, val listener: Listener)

    private val subs = CopyOnWriteArraySet<Sub>()

    fun publish(topic: String, payload: JsonElement?, origin: String) {
        for (s in subs) {
            val hit = (s.exact != null && s.exact == topic) || (s.prefix != null && topic.startsWith(s.prefix))
            if (hit) s.listener.onEvent(topic, payload, origin)
        }
    }

    /** Returns the unsubscribe action. */
    fun subscribe(topic: String, listener: Listener): () -> Unit {
        val sub = when {
            topic == "*" -> Sub(null, "", listener)
            topic.endsWith(".*") -> Sub(null, topic.dropLast(1), listener)
            else -> Sub(topic, null, listener)
        }
        subs += sub
        return { subs -= sub }
    }

    companion object {
        private const val AGENT_STATE_PREFIX = "agent-state:"
        private val RESERVED_PREFIXES = listOf(
            "telemetry.", "perception.", "theme.", "capability.", "config.",
            "lifecycle.", "video.overlay.", "i18n.",
        )

        fun agentStateOrigin(pluginId: String, node: DeviceId): String = "$AGENT_STATE_PREFIX${node.value}:$pluginId"

        fun isAgentStateOrigin(origin: String): Boolean = origin.startsWith(AGENT_STATE_PREFIX)

        fun isReservedTopic(topic: String): Boolean = RESERVED_PREFIXES.any { topic.startsWith(it) }
    }
}
