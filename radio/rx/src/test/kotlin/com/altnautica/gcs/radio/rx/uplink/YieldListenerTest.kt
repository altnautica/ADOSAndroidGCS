package com.altnautica.gcs.radio.rx.uplink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import kotlin.concurrent.thread

class YieldListenerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val served: MutableList<YieldRequest> = Collections.synchronizedList(mutableListOf())
    private val threads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var listener: YieldListener? = null

    private fun listen(deadlineMs: Long, maxConnections: Int): Int {
        val l = YieldListener(0, scope, deadlineMs, maxConnections) { request ->
            served += request
            threads += Thread.currentThread().name
            YieldAnswer(200, """{"ok":true}""")
        }
        listener = l
        assertTrue(l.start())
        return requireNotNull(l.localPort)
    }

    @After
    fun tearDown() {
        listener?.close()
        scope.cancel()
    }

    @Test
    fun aRequestInSmallPiecesIsReadWholeOnTheListenersOwnThreads() {
        val port = listen(deadlineMs = 3_000, maxConnections = 4)
        Socket("127.0.0.1", port).use { s ->
            s.tcpNoDelay = true
            s.soTimeout = 5_000
            val out = s.getOutputStream()
            for (piece in request().asList().chunked(7)) {
                out.write(piece.toByteArray())
                out.flush()
                Thread.sleep(5)
            }
            assertEquals("HTTP/1.1 200 OK", s.getInputStream().bufferedReader().readLine())
        }
        val request = served.single()
        assertEquals(TS, request.tsMs)
        assertEquals(MAC, request.macB64)
        assertEquals(BODY, request.body.decodeToString())
        assertTrue(threads.toString(), threads.single().startsWith(YieldListener.THREAD_NAME))
    }

    @Test
    fun aClientThatTricklesItsRequestIsCutOffAtTheDeadline() {
        val port = listen(deadlineMs = 500, maxConnections = 4)
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            val out = s.getOutputStream()
            out.write("POST /uplink/yield HTTP/1.1\r\n".encodeToByteArray())
            // One header byte every 50 ms: each read finds something, for five seconds.
            val trickle = thread(isDaemon = true) {
                try {
                    repeat(100) {
                        out.write('a'.code)
                        out.flush()
                        Thread.sleep(50)
                    }
                } catch (_: IOException) {
                    // The phone closed the connection.
                }
            }
            val started = System.nanoTime()
            val closed = closedByPeer(s)
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("the phone kept a trickling connection open", closed)
            assertTrue("closed only after $tookMs ms", tookMs < 3_000)
            trickle.join(6_000)
        }
        assertTrue(served.isEmpty())
    }

    @Test
    fun connectionsBeyondTheCapAreClosedUnanswered() {
        val port = listen(deadlineMs = 10_000, maxConnections = 2)
        val idle = List(2) { Socket("127.0.0.1", port) }
        try {
            Socket("127.0.0.1", port).use { extra ->
                extra.soTimeout = 2_000
                val started = System.nanoTime()
                assertTrue("a connection over the cap was kept open", closedByPeer(extra))
                assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500)
            }
        } finally {
            idle.forEach { it.close() }
        }
        // The slots free as the idle connections end; a call is served again.
        val deadline = System.nanoTime() + 3_000_000_000L
        var status: Int? = null
        while (status == null && System.nanoTime() < deadline) {
            status = call(port)
            if (status == null) Thread.sleep(50)
        }
        assertEquals(200, status)
        assertEquals(1, served.size)
    }

    /** True when the phone closed [s] (end of stream or a reset); false when the read timed out. */
    private fun closedByPeer(s: Socket): Boolean = try {
        s.getInputStream().read() == -1
    } catch (_: SocketTimeoutException) {
        false
    } catch (_: IOException) {
        true
    }

    /** One whole request; its status, or null when the phone closed the connection unanswered. */
    private fun call(port: Int): Int? = try {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write(request())
            s.getInputStream().bufferedReader().readLine()?.split(' ')?.get(1)?.toInt()
        }
    } catch (_: IOException) {
        null
    }

    private fun request(): ByteArray = (
        "POST /uplink/yield HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
            "X-ADOS-Uplink-Ts: $TS\r\nX-ADOS-Uplink-Mac: $MAC\r\nContent-Length: ${BODY.length}\r\nConnection: close\r\n\r\n$BODY"
        ).encodeToByteArray()

    private companion object {
        const val TS = 1_800_000_000_000L
        const val MAC = "bWFj"
        const val BODY = """{"box_device_id":"gs-box-1","op":"yield"}"""
    }
}
