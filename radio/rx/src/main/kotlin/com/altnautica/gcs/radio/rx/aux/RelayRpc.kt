package com.altnautica.gcs.radio.rx.aux

import com.altnautica.gcs.radio.rx.auxmux.AuxMuxDecoder
import java.io.ByteArrayOutputStream

/**
 * The relay-proxy request and response payloads carried on the aux lane's
 * Request and Response channels, as the drone's relay handler reads and
 * writes them. Big-endian throughout.
 *
 * A request object (`path_len u16 | path | content_type_len u8 | content_type
 * | ticket_len u16 | ticket | body`) is split into plain chunks, each led by
 * `method u8 | id u32 | target_len u8 | target | seq u16 | total u16`. A
 * response fragment is `sender_len u8 | sender | id u32 | status u16 |
 * frag_index u16 | frag_total u16 | oti u32 | frag_len u16 | symbol`, one
 * RaptorQ symbol of the response object.
 */
internal object RelayRpc {
    const val MAX_DEVICE_ID = 32
    const val REQUEST_OVERHEAD = 10
    const val MAX_REQUEST_CHUNK = AuxMuxDecoder.MAX_PAYLOAD - REQUEST_OVERHEAD - MAX_DEVICE_ID
    const val MAX_REQUEST_BODY = 1024 * 1024
    const val MAX_TICKET = 1024
    const val RESPONSE_OVERHEAD = 17
    const val MAX_RESPONSE_FRAGMENT = AuxMuxDecoder.MAX_PAYLOAD - RESPONSE_OVERHEAD - MAX_DEVICE_ID
    const val MAX_RESPONSE_FRAGMENTS = 64
    const val REPAIR_SYMBOLS = 4
    const val MAX_RESPONSE_BODY = (MAX_RESPONSE_FRAGMENTS - REPAIR_SYMBOLS) * MAX_RESPONSE_FRAGMENT
    const val HEADERS_FLAG = 0x8000

    /** The method byte; null for a method the relay does not carry. */
    fun methodByte(method: String): Int? = when (method) {
        "GET" -> 1
        "POST" -> 2
        "PUT" -> 3
        "DELETE" -> 4
        "PATCH" -> 5
        else -> null
    }

    /** The aux payloads (without the aux header) carrying one request, in send order; null when a field is too long. */
    fun encodeRequest(
        method: Int,
        id: Int,
        target: ByteArray,
        path: ByteArray,
        contentType: ByteArray,
        ticket: ByteArray,
        body: ByteArray,
    ): List<ByteArray>? {
        if (body.size > MAX_REQUEST_BODY || target.size > MAX_DEVICE_ID || path.size > 0xFFFF ||
            contentType.size > 0xFF || ticket.size > MAX_TICKET
        ) {
            return null
        }
        val obj = ByteArrayOutputStream(5 + path.size + contentType.size + ticket.size + body.size).apply {
            u16(path.size)
            write(path)
            write(contentType.size)
            write(contentType)
            u16(ticket.size)
            write(ticket)
            write(body)
        }.toByteArray()
        val chunks = (obj.indices step MAX_REQUEST_CHUNK).map { obj.copyOfRange(it, minOf(it + MAX_REQUEST_CHUNK, obj.size)) }
            .ifEmpty { listOf(ByteArray(0)) }
        return chunks.mapIndexed { seq, chunk ->
            ByteArrayOutputStream(REQUEST_OVERHEAD + target.size + chunk.size).apply {
                write(method)
                u32(id)
                write(target.size)
                write(target)
                u16(seq)
                u16(chunks.size)
                write(chunk)
            }.toByteArray()
        }
    }

    /** One response fragment, or null when the payload is not one. */
    fun decodeResponse(payload: ByteArray): ResponseFragment? {
        if (payload.isEmpty()) return null
        val senderLen = payload[0].toInt() and 0xFF
        if (senderLen > MAX_DEVICE_ID) return null
        val header = RESPONSE_OVERHEAD + senderLen
        if (payload.size < header) return null
        val f = 1 + senderLen
        val fragLen = payload.u16(f + 14)
        if (header + fragLen != payload.size) return null
        val index = payload.u16(f + 6)
        val total = payload.u16(f + 8)
        if (total == 0 || index >= total) return null
        return ResponseFragment(
            sender = payload.copyOfRange(1, f).decodeToString(),
            id = payload.u32(f),
            status = payload.u16(f + 4),
            index = index,
            total = total,
            oti = payload.u32(f + 10).toLong() and 0xFFFF_FFFFL,
            symbol = payload.copyOfRange(header, payload.size),
        )
    }

    /** The real status, body and headers of a reassembled response object. */
    fun unpack(wireStatus: Int, obj: ByteArray): RelayResponse {
        if (wireStatus and HEADERS_FLAG == 0) return RelayResponse(wireStatus, obj, emptyList())
        val status = wireStatus and HEADERS_FLAG.inv()
        val whole = RelayResponse(status, obj, emptyList())
        if (obj.size < 4) return whole
        val lenAt = obj.size - 4
        val blockLen = obj.u32(lenAt).toLong() and 0xFFFF_FFFFL
        if (blockLen > lenAt) return whole
        val blockAt = lenAt - blockLen.toInt()
        val headers = parseHeaders(obj, blockAt, lenAt) ?: return whole
        return RelayResponse(status, obj.copyOfRange(0, blockAt), headers)
    }

    private fun parseHeaders(obj: ByteArray, from: Int, to: Int): List<Pair<String, String>>? {
        val out = ArrayList<Pair<String, String>>()
        var at = from
        while (at < to) {
            val nameLen = obj[at].toInt() and 0xFF
            if (nameLen == 0 || at + 1 + nameLen + 2 > to) return null
            val name = obj.copyOfRange(at + 1, at + 1 + nameLen).decodeToString(throwOnInvalidSequence = true)
            at += 1 + nameLen
            val valueLen = obj.u16(at)
            at += 2
            if (at + valueLen > to) return null
            out += name to obj.copyOfRange(at, at + valueLen).decodeToString(throwOnInvalidSequence = true)
            at += valueLen
        }
        return out
    }

    /** The symbol size a response of [length] bytes uses. */
    fun symbolSize(length: Int): Int {
        val k = (length + MAX_RESPONSE_FRAGMENT - 1) / MAX_RESPONSE_FRAGMENT
        return (length + k - 1) / k
    }

    private fun ByteArrayOutputStream.u16(v: Int) {
        write(v ushr 8)
        write(v)
    }

    private fun ByteArrayOutputStream.u32(v: Int) {
        write(v ushr 24)
        write(v ushr 16)
        write(v ushr 8)
        write(v)
    }

    private fun ByteArray.u16(at: Int): Int = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

    private fun ByteArray.u32(at: Int): Int = (u16(at) shl 16) or u16(at + 2)
}

/** One RaptorQ symbol of a relayed response. */
internal class ResponseFragment(
    val sender: String,
    val id: Int,
    val status: Int,
    val index: Int,
    val total: Int,
    val oti: Long,
    val symbol: ByteArray,
)

/** A relayed call's answer: the drone's HTTP status, body and the headers that crossed. */
class RelayResponse(val status: Int, val body: ByteArray, val headers: List<Pair<String, String>>)
