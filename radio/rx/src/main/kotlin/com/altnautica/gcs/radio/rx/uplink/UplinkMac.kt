package com.altnautica.gcs.radio.rx.uplink

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * The MAC that authenticates the uplink handover in both directions: the
 * phone's take and give-back to the box, and the box's take-back call to the
 * phone. Both ends hold the same 32-byte handover key, delivered in the
 * ground station's sealed invite.
 *
 * `mac = HMAC-SHA256(key, "ados/gs-uplink/v1\n" || ts_ms || "\n" || body)`,
 * base64, where `ts_ms` is the decimal unix-millisecond timestamp the sender
 * puts in [TS_HEADER] and `body` is the exact request body bytes.
 */
object UplinkMac {
    private val CONTEXT = "ados/gs-uplink/v1\n".toByteArray(Charsets.US_ASCII)

    /** The phone's X25519 public key, base64 (phone to box only). */
    const val PHONE_KEY_HEADER = "X-ADOS-Phone-Key"

    /** The sender's unix-millisecond timestamp. */
    const val TS_HEADER = "X-ADOS-Uplink-Ts"

    /** The base64 MAC. */
    const val MAC_HEADER = "X-ADOS-Uplink-Mac"

    /** How far the sender's clock may sit from the receiver's. */
    const val CLOCK_WINDOW_MS = 120_000L

    const val KEY_BYTES = 32

    fun mac(key: ByteArray, tsMs: Long, body: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "the handover key is $KEY_BYTES bytes" }
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(key, "HmacSHA256"))
        hmac.update(CONTEXT)
        hmac.update(tsMs.toString().toByteArray(Charsets.US_ASCII))
        hmac.update('\n'.code.toByte())
        hmac.update(body)
        return hmac.doFinal()
    }

    fun macBase64(key: ByteArray, tsMs: Long, body: ByteArray): String =
        Base64.getEncoder().encodeToString(mac(key, tsMs, body))

    /** Whether [macB64] is the MAC of [tsMs] and [body] under [key], compared in constant time. */
    fun verify(key: ByteArray, tsMs: Long, body: ByteArray, macB64: String): Boolean {
        val got = runCatching { Base64.getDecoder().decode(macB64.trim()) }.getOrNull() ?: return false
        return MessageDigest.isEqual(got, mac(key, tsMs, body))
    }
}

/** Why a signed request was refused. */
enum class MacVerdict(val wire: String) {
    Accepted("ok"),
    Missing("missing"),
    Mismatch("bad_mac"),
    Clock("stale"),
    Replay("replay"),
}

/**
 * Accepts each signed request once: a good MAC, a timestamp inside the clock
 * window, and later than the last one accepted, so a captured request cannot
 * be played again.
 */
class UplinkReplayGuard {
    private var lastAcceptedMs: Long? = null

    @Synchronized
    fun check(key: ByteArray, tsMs: Long?, body: ByteArray, macB64: String?, nowMs: Long): MacVerdict {
        if (tsMs == null || macB64 == null) return MacVerdict.Missing
        if (!UplinkMac.verify(key, tsMs, body, macB64)) return MacVerdict.Mismatch
        if (abs(tsMs - nowMs) > UplinkMac.CLOCK_WINDOW_MS) return MacVerdict.Clock
        val last = lastAcceptedMs
        if (last != null && tsMs <= last) return MacVerdict.Replay
        lastAcceptedMs = tsMs
        return MacVerdict.Accepted
    }
}
