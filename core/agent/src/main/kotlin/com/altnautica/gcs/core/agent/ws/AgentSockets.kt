package com.altnautica.gcs.core.agent.ws

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentException
import com.altnautica.gcs.core.agent.http.AgentHttp
import com.altnautica.gcs.core.agent.http.AgentResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** One frame from an agent WebSocket. */
sealed interface WsFrame {
    data class Text(val text: String) : WsFrame
    data class Binary(val bytes: ByteArray) : WsFrame {
        override fun equals(other: Any?) = other is Binary && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
}

/**
 * Opens agent WebSockets. Native clients authenticate with the `X-ADOS-Key`
 * handshake header; routes proxied to the agent's residual API also require a
 * one-shot ticket offered as the `ados-ws-ticket` subprotocol.
 */
@Singleton
class AgentSockets @Inject constructor(private val http: AgentHttp) {

    /**
     * Frames from [path] on [client]'s node until the socket closes. The flow
     * completes on a normal close and fails with [AgentException] on close code
     * 4401 / HTTP 401 ([AgentError.Unauthorized]) or a transport error.
     */
    fun open(client: AgentClient, path: String, ticketScope: String? = null): Flow<WsFrame> = callbackFlow {
        val endpoint = client.endpoint
        var protocol: String? = null
        if (ticketScope != null) {
            when (val t = client.wsTicket(ticketScope)) {
                is AgentResult.Ok -> protocol = "$TICKET_PROTOCOL, ${t.value.ticket}"
                is AgentResult.Err -> {
                    close(AgentException(t.error))
                    return@callbackFlow
                }
            }
        }
        val httpUrl = endpoint.url(path)
        val wsUrl = httpUrl.toString().replaceFirst("http", "ws")
        val request = Request.Builder().url(wsUrl).apply {
            endpoint.apiKey?.let { header(AgentHttp.KEY_HEADER, it) }
            protocol?.let { header("Sec-WebSocket-Protocol", it) }
        }.build()
        val socket = http.webSocketClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    trySend(WsFrame.Text(text))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    trySend(WsFrame.Binary(bytes.toByteArray()))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSE, null)
                    if (code == UNAUTHORIZED_CLOSE) close(AgentException(AgentError.Unauthorized)) else close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (code == UNAUTHORIZED_CLOSE) close(AgentException(AgentError.Unauthorized)) else close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val error = when (response?.code) {
                        401, 403 -> AgentError.Unauthorized
                        404 -> AgentError.FeatureAbsent
                        null -> AgentError.Network(t as? IOException ?: IOException(t))
                        else -> AgentError.Server(response.code, response.message)
                    }
                    close(AgentException(error))
                }
            },
        )
        awaitClose { socket.close(NORMAL_CLOSE, null) }
    }

    companion object {
        const val TICKET_PROTOCOL = "ados-ws-ticket"
        const val UNAUTHORIZED_CLOSE = 4401
        const val NORMAL_CLOSE = 1000
    }
}
