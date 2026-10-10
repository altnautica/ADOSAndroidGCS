package com.altnautica.gcs.core.extensions.host

import com.altnautica.gcs.core.extensions.bridge.EnvelopeDispatcher
import com.altnautica.gcs.core.extensions.bridge.ExtensionEventBus
import com.altnautica.gcs.core.extensions.bridge.MountHandlers
import com.altnautica.gcs.core.extensions.bridge.RpcEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 *
 * Bridge handlers live only while a relay page is attached: [attach] builds a fresh
 * handler set from [newHandlers], and [detach] (the WebView left composition) disposes it
 * and cancels every in-flight request, so subscriptions, state polls, marks and pending
 * operator approvals never outlive the frame that asked for them.
 */
class ExtensionFrameSession(
    val pluginId: String,
    private val newHandlers: () -> MountHandlers,
    private val granted: () -> Set<String>,
    bus: ExtensionEventBus,
    agentStateOrigin: String,
    private val scope: CoroutineScope,
    private val onSecurityEvent: (code: String, method: String?) -> Unit = { _, _ -> },
) {
    private class Attachment(
        val send: (String) -> Unit,
        val handlers: MountHandlers,
        val dispatcher: EnvelopeDispatcher,
        val job: Job,
    )

    @Volatile
    private var attachment: Attachment? = null

    @Volatile
    var loaded: Boolean = false
        private set

    /** True while a relay page is attached. */
    val attached: Boolean get() = attachment != null

    private val lastHostEvents = LinkedHashMap<String, Pair<String, JsonElement?>>()

    private val stateSub = bus.subscribe("*") { topic, payload, origin ->
        if (origin == agentStateOrigin && !ExtensionEventBus.isReservedTopic(topic)) {
            postEnvelope(RpcEnvelope("state-" + UUID.randomUUID(), "event", topic, "", payload).toJson())
        }
    }

    /**
     * A relay page announced itself: bind [send] to it and create its handlers. A second
     * attach (the page reloaded) tears the previous handler set down first.
     */
    fun attach(send: (String) -> Unit) {
        detach()
        val handlers = newHandlers()
        val job = SupervisorJob(scope.coroutineContext[Job])
        val dispatcher = EnvelopeDispatcher(
            pluginId = pluginId,
            granted = granted,
            handlers = handlers.handlers,
            // A late reply from a detached page's handlers never reaches the next page.
            post = { env -> if (attachment?.job === job) postEnvelope(env) },
            onSecurityEvent = onSecurityEvent,
        )
        attachment = Attachment(send, handlers, dispatcher, job)
    }

    /** The relay page is gone: dispose its handlers and cancel its in-flight requests. */
    fun detach() {
        val a = attachment ?: return
        attachment = null
        loaded = false
        a.job.cancel()
        a.handlers.dispose()
    }

    /** Mount the frame document in the relay. */
    fun mount(html: String) {
        loaded = false
        attachment?.send?.invoke(buildJsonObject {
            put("kind", "mount")
            put("html", html)
        }.toString())
    }

    /** A message from the relay page (already origin-checked by the WebView listener). */
    fun onRelayMessage(text: String) {
        val a = attachment ?: return
        val msg = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return
        when ((msg["kind"] as? JsonPrimitive)?.content) {
            "loaded" -> {
                loaded = true
                replayHostEvents()
            }
            "envelope" -> {
                val env = msg["envelope"]
                scope.launch(a.job) { a.dispatcher.dispatch(env) }
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
        attachment?.send?.invoke(buildJsonObject {
            put("kind", "envelope")
            put("envelope", envelope)
        }.toString())
    }

    fun dispose() {
        detach()
        stateSub()
    }
}
