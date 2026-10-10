package com.altnautica.gcs.core.agent.groundstation

import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentException
import com.altnautica.gcs.core.agent.http.AgentJson
import com.altnautica.gcs.core.agent.ws.AgentSockets
import com.altnautica.gcs.core.agent.ws.WsFrame
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** The ground station's event streams. */
enum class GsStream(val path: String) {
    Uplink("api/v1/ground-station/ws/uplink"),
    Mesh("api/v1/ground-station/ws/mesh"),
    Buttons("api/v1/ground-station/ws/buttons"),
    Pic("api/v1/ground-station/pic/events"),
}

sealed interface GsEvent {
    /** One JSON event from the stream (keepalives are dropped). */
    data class Event(val payload: JsonObject) : GsEvent

    /** The stream dropped and is being re-dialled after [retryInMs]. */
    data class Reconnecting(val error: AgentError, val retryInMs: Long) : GsEvent

    /** Terminal: the ground station refused the key (close 4401) or does not serve the stream. */
    data class Refused(val error: AgentError) : GsEvent
}

/** Subscribes to ground-station event streams with 1 → 10 s reconnect backoff. */
@Singleton
class GsEvents @Inject constructor(
    private val clients: AgentClientFactory,
    private val sockets: AgentSockets,
) {

    fun events(ground: DeviceId, stream: GsStream): Flow<GsEvent> = flow {
        var backoff = MIN_BACKOFF_MS
        while (true) {
            val client = clients.forNode(ground)
            if (client == null) {
                emit(GsEvent.Refused(AgentError.Unauthorized))
                return@flow
            }
            val error: AgentError = try {
                sockets.open(client, stream.path).collect { frame ->
                    backoff = MIN_BACKOFF_MS
                    val text = (frame as? WsFrame.Text)?.text ?: return@collect
                    parse(text)?.let { emit(GsEvent.Event(it)) }
                }
                AgentError.Network(java.io.IOException("stream closed"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AgentException) {
                e.error
            }
            if (error is AgentError.Unauthorized || error is AgentError.FeatureAbsent || error is AgentError.ProfileMismatch) {
                emit(GsEvent.Refused(error))
                return@flow
            }
            emit(GsEvent.Reconnecting(error, backoff))
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    internal companion object {
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 10_000L

        /** The JSON object in [text], or null for a keepalive or a non-object frame. */
        fun parse(text: String): JsonObject? {
            val obj = runCatching { AgentJson.json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            val kind = (obj["kind"] as? JsonPrimitive)?.content
            return if (kind == "keepalive") null else obj
        }
    }
}
