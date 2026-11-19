package com.altnautica.gcs.radio.rx.aux

import com.altnautica.gcs.core.link.relay.RelayTicket
import com.altnautica.gcs.radio.rx.auxmux.AuxChannel
import com.altnautica.gcs.radio.rx.auxmux.AuxMuxDecoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** How a config-tunnel request ended. */
sealed interface TunnelResult {
    /** The drone answered: the config surface's JSON, or its error envelope when [isError]. */
    class Answered(val isError: Boolean, val body: ByteArray) : TunnelResult

    data class Failed(val reason: RelayFailure) : TunnelResult
}

/**
 * Agent config over the radio's config channel: one ticketed JSON op
 * (`get` a subtree, `put` one key) chunked into TUNNEL frames, one frame per
 * aux frame on the ConfigTunnel channel, and the drone's chunked answer
 * matched by the request id in the chunk header. The drone serves it only
 * when its operator opted the channel in; a drone that did not answers
 * nothing, and the call times out.
 *
 * Requests are addressed by their ticket: the uplink reaches every drone, and
 * only the drone the ticket was minted for (its device id) accepts it.
 */
class ConfigTunnelClient(
    private val send: (ByteArray) -> Boolean,
    private val nowS: () -> Long = { System.currentTimeMillis() / 1000 },
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private val nextId = AtomicInteger(1)
    private val sequence = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<TunnelResult.Answered>>()
    private val partial = ConcurrentHashMap<Int, Partial>()

    private class Partial(val total: Int, val isError: Boolean) {
        val chunks = arrayOfNulls<ByteArray>(total)
        var bytes = 0
    }

    suspend fun get(deviceId: String, key: String?, secretHex: String): TunnelResult =
        submit(deviceId, "get", key, null, secretHex)

    suspend fun put(deviceId: String, key: String, value: String, secretHex: String): TunnelResult =
        submit(deviceId, "put", key, value, secretHex)

    private suspend fun submit(deviceId: String, op: String, key: String?, value: String?, secretHex: String): TunnelResult {
        val body = requestBody(deviceId, op, key, value, secretHex, nowS(), RelayTicket.newNonce())
        val id = nextId.getAndIncrement()
        val frames = TunnelFrames.request(id, body, sequence.getAndAdd(64))
            ?.map { requireNotNull(AuxMuxDecoder.encode(AuxChannel.CONFIG_TUNNEL, it)) }
            ?: return TunnelResult.Failed(RelayFailure.NotRelayable)
        val answer = CompletableDeferred<TunnelResult.Answered>()
        pending[id] = answer
        try {
            var anySent = false
            for (f in frames) anySent = send(f) || anySent
            if (!anySent) return TunnelResult.Failed(RelayFailure.NotTransmitting)
            return withTimeoutOrNull(timeoutMs) { answer.await() } ?: TunnelResult.Failed(RelayFailure.Timeout)
        } finally {
            pending.remove(id)
            partial.remove(id)
        }
    }

    /** One ConfigTunnel-channel payload from the downlink (one TUNNEL frame). */
    fun onFrame(frame: ByteArray) {
        val chunk = TunnelFrames.parse(frame) ?: return
        if (!chunk.isResponse || chunk.total > TunnelFrames.MAX_CHUNKS) return
        val answer = pending[chunk.requestId] ?: return
        val done = synchronized(this) {
            val p = partial[chunk.requestId]?.takeIf { it.total == chunk.total && it.isError == chunk.isError }
                ?: Partial(chunk.total, chunk.isError).also { partial[chunk.requestId] = it }
            if (p.chunks[chunk.seq] == null) {
                p.chunks[chunk.seq] = chunk.data
                p.bytes += chunk.data.size
            }
            if (p.bytes > TunnelFrames.MAX_BODY) {
                partial.remove(chunk.requestId)
                return
            }
            if (p.chunks.any { it == null }) return
            partial.remove(chunk.requestId)
            p
        }
        val body = done.chunks.fold(ByteArray(0)) { acc, c -> acc + requireNotNull(c) }
        answer.complete(TunnelResult.Answered(done.isError, body))
    }

    companion object {
        /** The box's own default bound for a config request. */
        const val TIMEOUT_MS = 8_000L

        /**
         * The request JSON: the op, key and value, plus the ticket bound to
         * them, keys in sorted order as the box writes them.
         */
        fun requestBody(deviceId: String, op: String, key: String?, value: String?, secretHex: String, nowS: Long, nonce: String): ByteArray {
            val bare = buildJsonObject {
                key?.let { put("key", JsonPrimitive(it)) }
                put("op", JsonPrimitive(op))
                value?.let { put("value", JsonPrimitive(it)) }
            }
            val binding = RelayTicket.tunnelBindingBody(bare)
            val ticket = RelayTicket.forSecret(secretHex)
                .mint(deviceId, RelayTicket.TUNNEL_METHOD, RelayTicket.TUNNEL_PATH, binding, nowS, nonce)
            val full = JsonObject(bare.toMutableMap().apply { put("ticket", JsonPrimitive(ticket)) }.toSortedMap())
            return full.toString().toByteArray(Charsets.UTF_8)
        }
    }
}
