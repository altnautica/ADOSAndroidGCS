package com.altnautica.gcs.core.video.whep

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/**
 * An in-memory WHEP endpoint. It answers every call from a queue on the
 * calling thread, without sockets, so a [WhepSignaling] built on [client]
 * with a test dispatcher completes each exchange on the test scheduler and
 * virtual time never runs ahead of a request in flight.
 */
class FakeWhepEndpoint : Interceptor {

    data class Recorded(val method: String, val target: String, val headers: Headers, val body: String)

    private data class Reply(val code: Int, val location: String?, val body: String)

    private val replies = ArrayDeque<Reply>()
    private val recorded = mutableListOf<Recorded>()

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    val requests: List<Recorded> get() = synchronized(this) { recorded.toList() }

    fun answer(location: String = "/whep/abc?camera=main", sdp: String = "v=0 answer") =
        enqueue(201, location, sdp)

    fun enqueue(code: Int, location: String? = null, body: String = "") = synchronized(this) {
        replies.addLast(Reply(code, location, body))
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
        val target = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
        val reply = synchronized(this) {
            recorded += Recorded(request.method, target, request.headers, body)
            replies.removeFirstOrNull()
        } ?: throw IOException("no reply queued for ${request.method} $target")
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(reply.code)
            .message("fake")
            .apply { reply.location?.let { header("Location", it) } }
            .body(reply.body.toResponseBody())
            .build()
    }
}
