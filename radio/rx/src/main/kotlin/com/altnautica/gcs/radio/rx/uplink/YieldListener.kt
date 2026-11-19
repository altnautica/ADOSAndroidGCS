package com.altnautica.gcs.radio.rx.uplink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** One take-back call as it arrived: its timestamp and MAC headers and the exact body bytes. */
class YieldRequest(val tsMs: Long?, val macB64: String?, val body: ByteArray)

/** The answer to a take-back call: an HTTP status and a JSON body. */
data class YieldAnswer(val status: Int, val json: String)

/**
 * The phone's take-back endpoint: a plain [ServerSocket] serving one route,
 * `POST /uplink/yield`, which the box calls before its own operator takes
 * the uplink back. [handle] verifies the call and answers only once the
 * phone's transmitters stopped; every other path or method is refused here.
 */
class YieldListener(
    private val port: Int,
    private val scope: CoroutineScope,
    private val handle: suspend (YieldRequest) -> YieldAnswer,
) : AutoCloseable {

    private var server: ServerSocket? = null

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
        scope.launch(Dispatchers.IO) {
            while (true) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                scope.launch(Dispatchers.IO) { client.use { serve(it) } }
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
        client.soTimeout = READ_TIMEOUT_MS
        val answer = try {
            route(client.getInputStream())
        } catch (_: IOException) {
            return
        }
        val body = answer.json.encodeToByteArray()
        val head = "HTTP/1.1 ${answer.status} ${reason(answer.status)}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        runCatching {
            client.getOutputStream().apply {
                write(head.encodeToByteArray())
                write(body)
                flush()
            }
        }
    }

    private suspend fun route(input: InputStream): YieldAnswer {
        val head = readHead(input) ?: return YieldAnswer(400, error("E_BAD_REQUEST", "The request head is malformed."))
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
        val body = readExactly(input, length) ?: return YieldAnswer(400, error("E_BAD_REQUEST", "The request body ended early."))
        return handle(
            YieldRequest(
                tsMs = headers[UplinkMac.TS_HEADER.lowercase()]?.toLongOrNull(),
                macB64 = headers[UplinkMac.MAC_HEADER.lowercase()],
                body = body,
            ),
        )
    }

    /** The request line and headers, without the blank line that ends them. */
    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var tail = 0
        while (out.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            tail = (tail shl 8 or b) and 0xFFFFFFFF.toInt()
            if (tail == CRLFCRLF) return out.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    companion object {
        /** The fixed port the phone registers with the box at take. */
        const val PORT = 47811
        const val PATH = "/uplink/yield"
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_BODY = 4 * 1024
        private const val READ_TIMEOUT_MS = 3_000
        private const val CRLFCRLF = 0x0D0A0D0A

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
