package com.altnautica.gcs.radio.rx.uplink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One take-back call as it arrived: its timestamp and MAC headers and the exact body bytes. */
class YieldRequest(val tsMs: Long?, val macB64: String?, val body: ByteArray)

/** The answer to a take-back call: an HTTP status and a JSON body. */
data class YieldAnswer(val status: Int, val json: String)

/**
 * The phone's take-back endpoint: a plain [ServerSocket] serving one route,
 * `POST /uplink/yield`, which the box calls before its own operator takes
 * the uplink back. [handle] verifies the call and answers only once the
 * phone's transmitters stopped; every other path or method is refused here.
 *
 * Any host on the LAN can connect, so a connection costs the phone little:
 * the whole request must arrive within [deadlineMs] of the connection (a
 * client that trickles bytes is cut off as one that sends nothing), at most
 * [maxConnections] are served at once and any more are closed unanswered,
 * and the calls run on the listener's own few threads, never on the shared
 * IO pool the radio session's ticker and beacon run on.
 */
class YieldListener internal constructor(
    private val port: Int,
    private val scope: CoroutineScope,
    private val deadlineMs: Long,
    private val maxConnections: Int,
    private val handle: suspend (YieldRequest) -> YieldAnswer,
) : AutoCloseable {

    constructor(port: Int, scope: CoroutineScope, handle: suspend (YieldRequest) -> YieldAnswer) :
        this(port, scope, DEADLINE_MS, MAX_CONNECTIONS, handle)

    private var server: ServerSocket? = null
    private val slots = Semaphore(maxConnections)

    /** The accept loop and the calls being served; idle threads exit. */
    private val dispatcher = ThreadPoolExecutor(
        maxConnections + 1,
        maxConnections + 1,
        IDLE_S,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
    ) { r -> Thread(r, THREAD_NAME).apply { isDaemon = true } }
        .apply { allowCoreThreadTimeOut(true) }
        .asCoroutineDispatcher()

    /** The bound port (the requested one, or the system's pick for port 0); null while closed. */
    val localPort: Int? get() = server?.localPort

    /** Binds the port and starts serving; false when the port could not be bound. Idempotent. */
    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (_: IOException) {
            return false
        }
        server = socket
        // The loop ends when [close] closes the socket. A call being served is
        // not its child: the answer to a take-back goes out after the
        // handover has already closed the listener.
        scope.launch(dispatcher) {
            while (true) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                if (!slots.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                scope.launch(dispatcher) { serve(client) }.invokeOnCompletion {
                    runCatching { client.close() }
                    slots.release()
                }
            }
        }
        return true
    }

    /** Stops accepting; a call already being served still gets its answer. */
    @Synchronized
    override fun close() {
        runCatching { server?.close() }
        server = null
    }

    private suspend fun serve(client: Socket) {
        val answer = try {
            route(RequestReader(client, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs)))
        } catch (_: IOException) {
            // The client went away, or its request did not arrive whole in time.
            return
        }
        val body = answer.json.encodeToByteArray()
        val head = "HTTP/1.1 ${answer.status} ${reason(answer.status)}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        runCatching {
            client.getOutputStream().apply {
                write(head.encodeToByteArray() + body)
                flush()
            }
        }
    }

    private suspend fun route(reader: RequestReader): YieldAnswer {
        val (head, bodyStart) = reader.head() ?: return YieldAnswer(400, error("E_BAD_REQUEST", "The request head is malformed."))
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2 || parts[1].substringBefore('?') != PATH) {
            return YieldAnswer(404, error("E_NOT_FOUND", "This phone serves only $PATH."))
        }
        if (parts[0] != "POST") return YieldAnswer(405, error("E_METHOD", "$PATH takes POST only."))
        val headers = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull()
        if (length == null || length !in 0..MAX_BODY) {
            return YieldAnswer(400, error("E_BAD_REQUEST", "The request needs a Content-Length of at most $MAX_BODY bytes."))
        }
        val body = reader.body(bodyStart, length) ?: return YieldAnswer(400, error("E_BAD_REQUEST", "The request body ended early."))
        return handle(
            YieldRequest(
                tsMs = headers[UplinkMac.TS_HEADER.lowercase()]?.toLongOrNull(),
                macB64 = headers[UplinkMac.MAC_HEADER.lowercase()],
                body = body,
            ),
        )
    }

    /**
     * One connection's request, read in chunks into one buffer. Every read
     * waits only for what is left of the connection's deadline; past it the
     * read throws [SocketTimeoutException].
     */
    private class RequestReader(private val client: Socket, private val deadlineNanos: Long) {
        private val input = client.getInputStream()
        private val buf = ByteArray(MAX_HEAD + MAX_BODY)
        private var filled = 0

        /**
         * The request line and headers without the blank line that ends them,
         * and where the body starts; null when the stream ends first or the
         * head is longer than [MAX_HEAD].
         */
        fun head(): Pair<String, Int>? {
            var from = 0
            while (true) {
                val limit = minOf(filled, MAX_HEAD)
                for (i in from..limit - BLANK_LINE.size) {
                    if (BLANK_LINE.indices.all { buf[i + it] == BLANK_LINE[it] }) {
                        return String(buf, 0, i, Charsets.ISO_8859_1) to i + BLANK_LINE.size
                    }
                }
                if (filled >= MAX_HEAD || !fill()) return null
                from = maxOf(0, limit - BLANK_LINE.size + 1)
            }
        }

        /** [length] body bytes from [start]; null when the stream ends first. */
        fun body(start: Int, length: Int): ByteArray? {
            while (filled < start + length) {
                if (!fill()) return null
            }
            return buf.copyOfRange(start, start + length)
        }

        private fun fill(): Boolean {
            val leftMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            if (leftMs <= 0) throw SocketTimeoutException("the request did not arrive whole in time")
            client.soTimeout = leftMs.toInt()
            val n = input.read(buf, filled, buf.size - filled)
            if (n < 0) return false
            filled += n
            return true
        }
    }

    companion object {
        /** The fixed port the phone registers with the box at take. */
        const val PORT = 47811
        const val PATH = "/uplink/yield"

        /** The thread the listener accepts and serves on. */
        internal const val THREAD_NAME = "uplink-yield"
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_BODY = 4 * 1024
        private const val DEADLINE_MS = 3_000L
        private const val MAX_CONNECTIONS = 4
        private const val IDLE_S = 30L
        private val BLANK_LINE = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

        fun error(code: String, detail: String): String = buildJsonObject {
            put("error", JsonPrimitive(code))
            put("detail", JsonPrimitive(detail))
        }.toString()

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            else -> "Error"
        }
    }
}
