package com.altnautica.gcs.core.agent.http

import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The JSON configuration every agent payload is read and written with. */
object AgentJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }
}

/**
 * Where and how to reach one agent: its HTTP root, the key to present, and an
 * optional path prefix. A carried drone reached through its ground station is
 * the ground station's root and key plus the relay-proxy prefix.
 */
data class AgentEndpoint(
    /** The node these requests are for (the drone, for a relayed endpoint). */
    val deviceId: DeviceId?,
    /** HTTP root such as `http://192.168.1.50:8080/`. */
    val baseUrl: HttpUrl,
    val apiKey: String?,
    /** Path segments inserted before every request path (the relay-proxy prefix). */
    val pathPrefix: String = "",
    /** Set when requests travel through a ground-station relay to a carried drone. */
    val relayedVia: DeviceId? = null,
) {
    val isRelayed: Boolean get() = relayedVia != null

    /** The plain base URL string, e.g. for WHEP or the MAVLink WebSocket host. */
    val baseUrlString: String get() = baseUrl.toString()

    /** [path] must already be percent-encoded per segment (see `AgentClient.segment`). */
    fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val builder = baseUrl.newBuilder()
        val full = (pathPrefix.trim('/') + "/" + path.trimStart('/')).trim('/')
        if (full.isNotEmpty()) builder.encodedPath("/").addEncodedPathSegments(full)
        query.forEach { (k, v) -> if (v != null) builder.addQueryParameter(k, v) }
        return builder.build()
    }

    companion object {
        const val CONTROL_PORT = 8080
        const val MAVLINK_WS_PORT = 8765

        /** Build an endpoint from a host (`192.168.1.50`, `node.local`, `host:port`) or a full URL. */
        fun forHost(host: String, apiKey: String?, deviceId: DeviceId? = null): AgentEndpoint? {
            val trimmed = host.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val colons = trimmed.count { it == ':' }
            val candidate = when {
                trimmed.contains("://") -> trimmed
                trimmed.startsWith("[") -> if (trimmed.contains("]:")) "http://$trimmed" else "http://$trimmed:$CONTROL_PORT"
                colons == 1 -> "http://$trimmed"
                colons > 1 -> "http://[$trimmed]:$CONTROL_PORT"
                else -> "http://$trimmed:$CONTROL_PORT"
            }
            val url = "$candidate/".toHttpUrlOrNull() ?: return null
            return AgentEndpoint(deviceId, url.newBuilder().encodedPath("/").build(), apiKey)
        }
    }
}

/** A fully read agent response. */
class AgentResponse(val code: Int, val bytes: ByteArray, val headers: okhttp3.Headers) {
    val text: String get() = bytes.decodeToString()
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * The one HTTP transport for agent traffic. Each call targets an explicit
 * [AgentEndpoint]; the key rides only in `X-ADOS-Key`, never in a URL, and
 * nothing logs request or response bodies.
 */
@Singleton
class AgentHttp @Inject constructor(base: OkHttpClient) {

    private val lan: OkHttpClient = base.newBuilder()
        .connectTimeout(LAN_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(LAN_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(LAN_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val slow: OkHttpClient = base.newBuilder()
        .connectTimeout(LAN_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(RELAY_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(RELAY_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /** The client WebSockets are opened on (no read timeout; agents send keepalives). */
    val webSocketClient: OkHttpClient = base.newBuilder()
        .connectTimeout(LAN_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(WS_PING_S, TimeUnit.SECONDS)
        .build()

    /**
     * Send one request. [slowCall] selects the 15 s budget used for relayed
     * calls and for writes that restart a service on the agent.
     */
    suspend fun execute(
        endpoint: AgentEndpoint,
        method: String,
        path: String,
        query: Map<String, String?> = emptyMap(),
        jsonBody: String? = null,
        slowCall: Boolean = false,
    ): AgentResult<AgentResponse> {
        val url = endpoint.url(path, query)
        val body = when {
            jsonBody != null -> jsonBody.toRequestBody(JSON_MEDIA)
            method == "POST" || method == "PUT" || method == "PATCH" -> "{}".toRequestBody(JSON_MEDIA)
            else -> null
        }
        val request = Request.Builder().url(url).method(method, body).apply {
            endpoint.apiKey?.let { header(KEY_HEADER, it) }
        }.build()
        val client = if (slowCall || endpoint.isRelayed) slow else lan
        return try {
            client.newCall(request).await().use { response ->
                val read = AgentResponse(response.code, response.body.bytes(), response.headers)
                if (read.isSuccessful) {
                    AgentResult.Ok(read)
                } else {
                    AgentResult.Err(AgentErrorParser.parse(read.code, read.text, endpoint.isRelayed))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AgentResult.Err(AgentError.Network(e))
        }
    }

    companion object {
        /** The header the agent's LAN edge reads the pairing key from. */
        const val KEY_HEADER = "X-ADOS-Key"
        const val LAN_TIMEOUT_S = 6L
        const val RELAY_TIMEOUT_S = 15L
        const val WS_PING_S = 15L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** Run the call on OkHttp's dispatcher; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        },
    )
}
