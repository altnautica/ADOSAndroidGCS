package com.altnautica.gcs.core.extensions.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** One RPC envelope on the extension bridge (protocol version 1). */
data class RpcEnvelope(
    val id: String,
    val type: String,
    val method: String,
    val capability: String,
    val args: JsonElement?,
    val error: RpcError? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
        put("method", method)
        put("capability", capability)
        put("args", args ?: JsonNull)
        put("version", 1)
        if (error != null) {
            put("error", buildJsonObject {
                put("code", error.code)
                put("message", error.message)
            })
        }
    }

    companion object {
        /** Structural validation; anything else is dropped as a security event. */
        fun parse(value: JsonElement?): RpcEnvelope? {
            val o = value as? JsonObject ?: return null
            if ((o["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull != 1) return null
            val id = o.string("id")?.takeIf { it.isNotEmpty() } ?: return null
            val type = o.string("type")?.takeIf { it == "request" || it == "response" || it == "event" } ?: return null
            val method = o.string("method")?.takeIf { it.isNotEmpty() } ?: return null
            val capability = o.string("capability") ?: return null
            return RpcEnvelope(id, type, method, capability, o["args"])
        }

        private fun JsonObject.string(k: String): String? =
            (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

data class RpcError(val code: String, val message: String)

/** A handler failure that answers with a specific error code. */
class BridgeException(val code: String, message: String) : Exception(message)

/** Context passed to a handler. */
class HandlerScope(
    val pluginId: String,
    val capability: String?,
    val postEvent: (method: String, capability: String, args: JsonElement?) -> Unit,
)

typealias BridgeHandler = suspend HandlerScope.(args: JsonElement?) -> JsonElement?

/**
 * Validates envelopes from one mounted extension, enforces method → capability rules
 * against the node's granted capabilities, runs the handler and posts the response.
 */
class EnvelopeDispatcher(
    private val pluginId: String,
    private val granted: () -> Set<String>,
    private val handlers: Map<String, BridgeHandler>,
    private val post: (JsonObject) -> Unit,
    private val onSecurityEvent: (code: String, method: String?) -> Unit = { _, _ -> },
) {
    private val scopeFor: (String?) -> HandlerScope = { cap -> HandlerScope(pluginId, cap, ::pushEvent) }

    fun pushEvent(method: String, capability: String, args: JsonElement?) {
        post(RpcEnvelope(UUID.randomUUID().toString(), "event", method, capability, args).toJson())
    }

    suspend fun dispatch(raw: JsonElement?) {
        val env = RpcEnvelope.parse(raw)
        if (env == null) {
            onSecurityEvent("schema_invalid", null)
            return
        }
        if (env.type != "request") return
        if (!MethodRules.isKnown(env.method)) {
            deny(env, "method_unknown", "unknown method ${env.method}")
            return
        }
        val capability = when (val r = MethodRules.required(env.method, env.args)) {
            MethodRules.Required.Unknown -> {
                deny(env, "schema_invalid", "bad args for ${env.method}")
                return
            }
            MethodRules.Required.Always -> null
            is MethodRules.Required.Capability -> r.id
        }
        if (capability != null && !MethodRules.isGranted(capability, granted())) {
            deny(env, "permission_denied", "extension lacks capability $capability")
            return
        }
        val handler = handlers[env.method]
        if (handler == null) {
            deny(env, "handler_unset", "no handler registered for ${env.method}")
            return
        }
        try {
            val result = scopeFor(capability).handler(env.args)
            respond(env, result, null)
        } catch (c: CancellationException) {
            throw c
        } catch (b: BridgeException) {
            deny(env, b.code, b.message ?: b.code)
        } catch (t: Throwable) {
            deny(env, "handler_error", t.message ?: t.javaClass.simpleName)
        }
    }

    private fun deny(env: RpcEnvelope, code: String, message: String) {
        onSecurityEvent(code, env.method)
        respond(env, null, RpcError(code, message))
    }

    private fun respond(env: RpcEnvelope, result: JsonElement?, error: RpcError?) {
        post(RpcEnvelope(env.id, "response", env.method, env.capability, result ?: JsonNull, error).toJson())
    }
}
