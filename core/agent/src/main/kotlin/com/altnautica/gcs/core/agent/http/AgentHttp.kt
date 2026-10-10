package com.altnautica.gcs.core.agent.http

import com.altnautica.gcs.core.agent.pairing.NotPairedError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Raised when the agent answered with a non-2xx status other than the ones a
 * client maps to a more specific error.
 */
class AgentHttpException(val code: Int, message: String) : IOException(message)

/** A fully read agent response: status code plus the body text. */
data class AgentResponse(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Shared JSON-over-HTTP transport for the agent's REST surface.
 *
 * Each call resolves its path against the base URL configured at call time,
 * runs on the IO dispatcher, and reads the whole body before returning so no
 * response stays open past the call.
 */
@Singleton
class AgentHttp @Inject constructor(
    private val client: OkHttpClient,
    val json: Json,
    private val baseUrlProvider: BaseUrlProvider,
) {

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    /** Send [method] to [path] (relative, e.g. `api/v1/...`) with an optional JSON body. */
    suspend fun send(method: String, path: String, jsonBody: String? = null): AgentResponse =
        withContext(Dispatchers.IO) {
            val base = baseUrlProvider.currentBaseUrl().toHttpUrlOrNull()
                ?: throw IOException("configured agent base URL is not a valid HTTP URL")
            val url = base.resolve(path)
                ?: throw IOException("cannot resolve $path against the agent base URL")
            val body: RequestBody? = when {
                jsonBody != null -> jsonBody.toRequestBody(JSON_MEDIA)
                method == "POST" || method == "PUT" -> "".toRequestBody(JSON_MEDIA)
                else -> null
            }
            val request = Request.Builder().url(url).method(method, body).build()
            client.newCall(request).await().use { response ->
                AgentResponse(response.code, response.body.string())
            }
        }

    /** Send a request whose body is [value] encoded with [serializer]. */
    suspend fun <B> send(
        method: String,
        path: String,
        serializer: SerializationStrategy<B>,
        value: B,
    ): AgentResponse = send(method, path, json.encodeToString(serializer, value))

    /**
     * Decode a successful response, or throw: 401 becomes [NotPairedError]
     * (the node answered and refused the credential, which the UI words
     * differently from an unreachable node), any other non-2xx becomes
     * [AgentHttpException].
     */
    fun <T> decode(response: AgentResponse, deserializer: DeserializationStrategy<T>): T {
        requireSuccess(response)
        return json.decodeFromString(deserializer, response.body)
    }

    /** Throw the same errors as [decode] for a response whose body is not needed. */
    fun requireSuccess(response: AgentResponse) {
        if (response.isSuccessful) return
        if (response.code == 401) {
            throw NotPairedError("this node is paired; pair this device in Settings")
        }
        throw AgentHttpException(response.code, "agent request failed: HTTP ${response.code}")
    }
}

/** Run the call on OkHttp's dispatcher and resume with its response; cancelling cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
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
