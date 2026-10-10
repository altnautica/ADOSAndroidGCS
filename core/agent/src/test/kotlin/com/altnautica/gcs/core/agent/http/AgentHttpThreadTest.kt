package com.altnautica.gcs.core.agent.http

import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * A composable effect calls the agent from the main thread. Reading the body
 * there is a NetworkOnMainThreadException on Android (the fleet snapshot crash),
 * so the body must be read on another thread than the caller's.
 */
class AgentHttpThreadTest {

    private val server = MockWebServer()
    private val readers = ConcurrentLinkedQueue<String>()

    /** Records the thread of every body read. */
    private val recording = OkHttpClient.Builder().addInterceptor { chain ->
        val response = chain.proceed(chain.request())
        val body = response.body
        val source = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readers += Thread.currentThread().name
                return super.read(sink, byteCount)
            }
        }.buffer()
        response.newBuilder().body(source.asResponseBody(body.contentType(), body.contentLength())).build()
    }.build()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    @Test
    fun `body is never read on the calling thread`() {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(payload)).build())
        val caller = Executors.newSingleThreadExecutor { Thread(it, "ui-caller") }
        try {
            val result = runBlocking {
                withContext(caller.asCoroutineDispatcher()) {
                    AgentHttp(recording).execute(AgentEndpoint(DeviceId("d1"), server.url("/"), "k"), "GET", "api/video/snapshot")
                }
            }
            val ok = result as AgentResult.Ok
            assertEquals(payload.size, ok.value.bytes.size)
            assertTrue(readers.isNotEmpty())
            assertFalse("body read on the caller thread", "ui-caller" in readers)
        } finally {
            caller.shutdown()
        }
    }
}
