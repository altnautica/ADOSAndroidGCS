package com.altnautica.gcs.radio.rx.aux

import com.altnautica.gcs.core.link.relay.RelayTicket
import com.altnautica.gcs.radio.rx.auxmux.AuxChannel
import com.altnautica.gcs.radio.rx.auxmux.AuxMuxDecoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** How a relayed call ended. */
sealed interface RelayResult {
    /** The drone answered; its own HTTP status is inside. */
    class Answered(val response: RelayResponse) : RelayResult

    /** Nothing usable came back. */
    data class Failed(val reason: RelayFailure) : RelayResult
}

enum class RelayFailure {
    /** The phone does not hold the uplink, or its uplink is down. */
    NotTransmitting,

    /** The request does not fit the relay (method, field lengths, body). */
    NotRelayable,

    /** No fragment of an answer arrived within the call's bound. */
    Timeout,

    /** Fragments arrived but too few to rebuild the answer. */
    Incomplete,
}

/**
 * Node-API calls to one drone over the aux uplink's Request lane, answered
 * on the downlink's Response lane, the way a ground station's relay proxy
 * calls a drone it has no IP reach to: the request travels as plain chunks,
 * resent on a growing schedule until the first fragment of the answer
 * arrives (the drone answers a repeat from its cache), and the answer is a
 * RaptorQ-coded object rebuilt from any sufficient set of fragments from the
 * addressed drone. A call is bounded by [timeoutMs] once the request is on
 * the air.
 *
 * [send] puts one aux-mux frame on the uplink and is false while the phone
 * cannot transmit; [onResponse] takes every Response-channel payload the
 * session receives.
 */
class RelayRequestClient(
    private val send: (ByteArray) -> Boolean,
    private val nowS: () -> Long = { System.currentTimeMillis() / 1000 },
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private val nextId = AtomicInteger(Random.nextInt() or 1)
    private val pending = ConcurrentHashMap<Int, Pending>()

    private class Pending(val sender: String) {
        val answer = CompletableDeferred<RelayResponse>()

        @Volatile var started = false
        var decoder: RaptorDecoder? = null
        var status = 0
        var total = 0
        var received = 0
    }

    /**
     * One request to [deviceId]. [secretHex] non-null: the request carries a
     * relay ticket minted from that per-pair secret, bound to [method], [path]
     * (with its query, still encoded) and [body]; null: no ticket.
     */
    suspend fun call(
        deviceId: String,
        method: String,
        path: String,
        contentType: String?,
        body: ByteArray,
        secretHex: String?,
    ): RelayResult {
        val methodByte = RelayRpc.methodByte(method) ?: return RelayResult.Failed(RelayFailure.NotRelayable)
        val ticket = secretHex?.let { RelayTicket.forSecret(it).mint(deviceId, method, path, body, nowS()) }.orEmpty()
        val id = nextId.getAndIncrement()
        val fragments = RelayRpc.encodeRequest(
            methodByte,
            id,
            deviceId.toByteArray(Charsets.UTF_8),
            path.toByteArray(Charsets.UTF_8),
            contentType.orEmpty().toByteArray(Charsets.UTF_8),
            ticket.toByteArray(Charsets.UTF_8),
            body,
        )?.map { requireNotNull(AuxMuxDecoder.encode(AuxChannel.REQUEST, it)) }
            ?: return RelayResult.Failed(RelayFailure.NotRelayable)
        val call = Pending(deviceId)
        pending[id] = call
        try {
            if (!sendAll(fragments)) return RelayResult.Failed(RelayFailure.NotTransmitting)
            val answer = withTimeoutOrNull(timeoutMs) {
                for (gap in RETRY_MS) {
                    val got = withTimeoutOrNull(gap) { call.answer.await() }
                    if (got != null) return@withTimeoutOrNull got
                    if (!call.started) sendAll(fragments)
                }
                call.answer.await()
            }
            return when {
                answer != null -> RelayResult.Answered(answer)
                call.decoder != null -> RelayResult.Failed(RelayFailure.Incomplete)
                else -> RelayResult.Failed(RelayFailure.Timeout)
            }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun sendAll(frames: List<ByteArray>): Boolean {
        frames.forEachIndexed { i, f ->
            if (!send(f)) return false
            if (i < frames.lastIndex) delay(FRAGMENT_PACING_MS)
        }
        return true
    }

    /** One Response-channel payload from the downlink. */
    fun onResponse(payload: ByteArray) {
        val frag = RelayRpc.decodeResponse(payload) ?: return
        val call = pending[frag.id] ?: return
        // Another aircraft answering the same id says nothing about ours.
        if (frag.sender != call.sender) return
        call.started = true
        synchronized(call) {
            if (call.answer.isCompleted) return
            if (call.decoder == null) {
                if (frag.total > RelayRpc.MAX_RESPONSE_FRAGMENTS || frag.oti > RelayRpc.MAX_RESPONSE_BODY) {
                    call.answer.complete(RelayResponse(502, "relay response geometry out of range".toByteArray(), emptyList()))
                    return
                }
                if (frag.oti == 0L) {
                    call.answer.complete(RelayRpc.unpack(frag.status, ByteArray(0)))
                    return
                }
                val len = frag.oti.toInt()
                call.decoder = RaptorDecoder(len, RelayRpc.symbolSize(len))
                call.status = frag.status
                call.total = frag.total
            }
            val decoder = requireNotNull(call.decoder)
            if (frag.total != call.total || frag.index >= decoder.k + RelayRpc.REPAIR_SYMBOLS) return
            val obj = decoder.push(frag.index, frag.symbol) ?: return
            call.answer.complete(RelayRpc.unpack(call.status, obj))
        }
    }

    companion object {
        /** Above the drone's own 5 s HTTP bound, so a wedged drone API answers as the drone's 503. */
        const val TIMEOUT_MS = 10_000L

        /** Gaps between resends, non-harmonic with the video frame period. */
        val RETRY_MS = longArrayOf(150, 250, 400, 700)
        const val FRAGMENT_PACING_MS = 5L
    }
}
