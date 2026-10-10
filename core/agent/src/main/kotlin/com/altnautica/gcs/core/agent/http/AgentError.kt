package com.altnautica.gcs.core.agent.http

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

/** Every way an agent request can fail, normalised from the agent's several error shapes. */
sealed interface AgentError {
    /** 401, or a 403 refusing an unpaired caller: the stored key is missing or wrong. */
    data object Unauthorized : AgentError

    /** 403 on an unpaired node that wants a dashboard PIN session. */
    data class PinRequired(val message: String?) : AgentError

    /** 409 `E_ARMED`: the vehicle is armed; [canForce] when the agent accepts `force`. */
    data class Armed(val canForce: Boolean) : AgentError

    /** 404 `E_PROFILE_MISMATCH`: the route belongs to another node profile. */
    data object ProfileMismatch : AgentError

    /** The drone refused the ground station's relay ticket (`E_RELAY_TICKET`). */
    data class RelayTicket(val reason: String?) : AgentError

    /** The relay could not reach the peer (`E_PEER_*` / `E_RELAY_*`, or a 502/504 from the relay). */
    data class PeerApi(val code: String?, val message: String?) : AgentError

    /** 404 or 501 without a more specific code: the agent does not serve this feature. */
    data object FeatureAbsent : AgentError

    /** Transport failure: the node could not be reached. */
    data class Network(val cause: IOException) : AgentError

    /** Any other non-2xx answer, or a 2xx body that does not decode. */
    data class Server(val code: Int, val message: String?) : AgentError
}

/** Result of one agent call. */
sealed interface AgentResult<out T> {
    data class Ok<T>(val value: T) : AgentResult<T>
    data class Err(val error: AgentError) : AgentResult<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): AgentError? = (this as? Err)?.error
}

inline fun <T, R> AgentResult<T>.map(transform: (T) -> R): AgentResult<R> = when (this) {
    is AgentResult.Ok -> AgentResult.Ok(transform(value))
    is AgentResult.Err -> this
}

inline fun <T, R> AgentResult<T>.flatMap(transform: (T) -> AgentResult<R>): AgentResult<R> = when (this) {
    is AgentResult.Ok -> transform(value)
    is AgentResult.Err -> this
}

/** Thrown only where a flow has to surface an [AgentError] as an exception. */
class AgentException(val error: AgentError) : IOException(error.toString())

/** Maps a non-2xx status and body to an [AgentError]. */
object AgentErrorParser {

    fun parse(status: Int, body: String, relayed: Boolean = false): AgentError {
        val json = runCatching { AgentJson.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val topCode = json?.str("error")
        val detail = json?.get("detail")
        val detailObj = detail as? JsonObject
        val nested = (detailObj?.get("error") as? JsonObject)
        val code = topCode ?: nested?.str("code") ?: detailObj?.str("code")
        val message = json?.str("message")
            ?: nested?.str("message")
            ?: (detail as? JsonPrimitive)?.contentOrNull
            ?: detailObj?.str("message")
            ?: json?.str("reason")

        when (code) {
            "E_ARMED" -> return AgentError.Armed(
                canForce = json?.str("override") == "force" || detailObj?.str("override") == "force",
            )
            "E_PROFILE_MISMATCH" -> return AgentError.ProfileMismatch
            "E_RELAY_TICKET" -> return AgentError.RelayTicket(json?.str("reason") ?: nested?.str("reason"))
        }
        if (code != null && (code.startsWith("E_RELAY_") || code.startsWith("E_PEER_"))) {
            return AgentError.PeerApi(code, message)
        }
        return when (status) {
            401 -> AgentError.Unauthorized
            403 -> if (message?.contains("PIN", ignoreCase = false) == true) {
                AgentError.PinRequired(message)
            } else {
                AgentError.Unauthorized
            }
            404, 501 -> if (code == null) AgentError.FeatureAbsent else AgentError.Server(status, message ?: code)
            502, 504 -> if (relayed) AgentError.PeerApi(code, message) else AgentError.Server(status, message)
            else -> AgentError.Server(status, message ?: code)
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
