package com.altnautica.gcs.core.extensions.host

import com.altnautica.gcs.core.extensions.bridge.EnvelopeDispatcher
import com.altnautica.gcs.core.extensions.bridge.ExtensionEventBus
import com.altnautica.gcs.core.extensions.bridge.MountHandlers
import com.altnautica.gcs.core.extensions.bridge.RpcEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The app side of one relay page: turns relay messages into bridge dispatches and
 * forwards responses, host events and the extension's own agent state into the frame.
 * [send] delivers a JSON string to the relay page (null while no page is attached).
 */
class ExtensionFrameSession(
    val pluginId: String,
    private val handlers: MountHandlers,
    granted: () -> Set<String>,
    private val bus: ExtensionEventBus,
    agentStateOrigin: String,
    private val scope: CoroutineScope,
    onSecurityEvent: (code: String, method: String?) -> Unit = { _, _ -> },
) {
    @Volatile
    var send: ((String) -> Unit)? = null

    @Volatile
    var loaded: Boolean = false
        private set

    private val lastHostEvents = LinkedHashMap<String, Pair<String, JsonElement?>>()

    private val dispatcher = EnvelopeDispatcher(
        pluginId = pluginId,
        granted = granted,
        handlers = handlers.handlers,
        post = ::postEnvelope,
        onSecurityEvent = onSecurityEvent,
    )

    private val stateSub = bus.subscribe("*") { topic, payload, origin ->
        if (origin == agentStateOrigin && !ExtensionEventBus.isReservedTopic(topic)) {
            postEnvelope(RpcEnvelope("state-" + UUID.randomUUID(), "event", topic, "", payload).toJson())
        }
    }

    /** Mount the frame document in the relay. */
    fun mount(html: String) {
        loaded = false
        send?.invoke(buildJsonObject {
            put("kind", "mount")
            put("html", html)
        }.toString())
    }

    /** A message from the relay page (already origin-checked by the WebView listener). */
    fun onRelayMessage(text: String) {
        val msg = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return
        when ((msg["kind"] as? JsonPrimitive)?.content) {
            "loaded" -> {
                loaded = true
                replayHostEvents()
            }
            "envelope" -> {
                val env = msg["envelope"]
                scope.launch { dispatcher.dispatch(env) }
            }
        }
    }

    /**
     * A one-way host event (`theme.changed`, `config.changed`, `video.overlay.props`). The
     * latest value of each is kept and replayed when the frame (re)loads.
     */
    fun hostEvent(method: String, capability: String, args: JsonElement?) {
        synchronized(lastHostEvents) { lastHostEvents[method] = capability to args }
        if (loaded) postEnvelope(RpcEnvelope("host-" + UUID.randomUUID(), "event", method, capability, args).toJson())
    }

    private fun replayHostEvents() {
        val events = synchronized(lastHostEvents) { lastHostEvents.toList() }
        for ((method, v) in events) {
            postEnvelope(RpcEnvelope("host-" + UUID.randomUUID(), "event", method, v.first, v.second).toJson())
        }
    }

    private fun postEnvelope(envelope: JsonObject) {
        send?.invoke(buildJsonObject {
            put("kind", "envelope")
            put("envelope", envelope)
        }.toString())
    }

    fun dispose() {
        stateSub()
        handlers.dispose()
        send = null
    }
}
