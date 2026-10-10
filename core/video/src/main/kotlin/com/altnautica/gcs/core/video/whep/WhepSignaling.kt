package com.altnautica.gcs.core.video.whep

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The answer to a WHEP offer: the remote SDP and the session resource to DELETE. */
data class WhepAnswer(val sdp: String, val resourceUrl: HttpUrl?)

class WhepException(message: String, val httpCode: Int? = null) : IOException(message)

/**
 * The HTTP half of WHEP:
 *
 *     POST {base}/whep[?camera=id]   Content-Type: application/sdp, X-ADOS-Key
 *     201  Location: /whep/<session>?camera=<id>   body: answer SDP
 *     DELETE <Location resolved against the base>
 */
class WhepSignaling(private val client: OkHttpClient) {

    /**
     * POSTs the offer. The exchange runs to completion even when the caller is
     * cancelled mid-flight: a 201 that arrives after cancellation has created a
     * session on the agent, so its Location is DELETEd before the cancellation
     * is rethrown. Nothing the agent created is left behind.
     */
    suspend fun offer(baseUrl: String, cameraId: String?, apiKey: String?, offerSdp: String): WhepAnswer {
        val answer = withContext(NonCancellable + Dispatchers.IO) { post(baseUrl, cameraId, apiKey, offerSdp) }
        if (!currentCoroutineContext().isActive) {
            answer.resourceUrl?.let { withContext(NonCancellable) { delete(it, apiKey) } }
            currentCoroutineContext().ensureActive()
        }
        return answer
    }

    private fun post(baseUrl: String, cameraId: String?, apiKey: String?, offerSdp: String): WhepAnswer {
        val url = whepUrl(baseUrl, cameraId)
        val request = Request.Builder()
            .url(url)
            .post(offerSdp.toRequestBody(SDP))
            .apply { if (!apiKey.isNullOrEmpty()) header(KEY_HEADER, apiKey) }
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw WhepException("WHEP ${response.code}", response.code)
            }
            val body = response.body.string()
            if (body.isBlank()) throw WhepException("WHEP answer was empty", response.code)
            WhepAnswer(body, response.header("Location")?.let { resolveResource(url, it) })
        }
    }

    /** Best effort: a teardown proceeds whether or not the DELETE lands. */
    suspend fun delete(resourceUrl: HttpUrl, apiKey: String?) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(resourceUrl)
                .delete()
                .apply { if (!apiKey.isNullOrEmpty()) header(KEY_HEADER, apiKey) }
                .build()
            val shortLived = client.newBuilder().callTimeout(DELETE_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
            runCatching { shortLived.newCall(request).execute().close() }
        }
    }

    companion object {
        const val KEY_HEADER = "X-ADOS-Key"
        private const val DELETE_TIMEOUT_MS = 2_000L
        private val SDP = "application/sdp".toMediaType()

        /** `{base}/whep`, with `?camera=` selecting a concurrent leg when given. */
        fun whepUrl(baseUrl: String, cameraId: String?): HttpUrl {
            val raw = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val base = raw.toHttpUrlOrNull() ?: throw WhepException("Invalid base URL")
            val url = base.resolve("whep") ?: throw WhepException("Invalid base URL")
            return if (cameraId.isNullOrEmpty()) url
            else url.newBuilder().addQueryParameter("camera", cameraId).build()
        }

        /** Resolves a relative or absolute `Location` against the request URL. */
        fun resolveResource(requestUrl: HttpUrl, location: String): HttpUrl? =
            requestUrl.resolve(location)
    }
}
