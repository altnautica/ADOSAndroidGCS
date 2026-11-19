package com.altnautica.gcs.radio.rx.uplink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * The MAC that authenticates the uplink handover: the phone's take and
 * give-back to the box, and the box's take-back call to the phone. Both ends
 * hold the same 32-byte handover key, delivered in the ground station's
 * sealed invite.
 *
 * `mac = HMAC-SHA256(key, context || ts_ms || "\n" || body)`, base64, where
 * the context names the direction ([Domain]), `ts_ms` is the decimal
 * unix-millisecond timestamp the sender puts in [TS_HEADER] and `body` is the
 * exact request body bytes. Each direction has its own context, so a request
 * the phone signed never verifies as a take-back call, however it is replayed.
 */
object UplinkMac {
    /** Which way a signed request goes. */
    enum class Domain(context: String) {
        /** The phone's take and give-back to the box. */
        Request("ados/gs-uplink/v1\n"),

        /** The box's take-back call to the phone. */
        Yield("ados/gs-uplink-yield/v1\n"),
        ;

        internal val context: ByteArray = context.toByteArray(Charsets.US_ASCII)
    }

    /** The phone's X25519 public key, base64 (phone to box only). */
    const val PHONE_KEY_HEADER = "X-ADOS-Phone-Key"

    /** The sender's unix-millisecond timestamp. */
    const val TS_HEADER = "X-ADOS-Uplink-Ts"

    /** The base64 MAC. */
    const val MAC_HEADER = "X-ADOS-Uplink-Mac"

    /** How far the sender's clock may sit from the receiver's. */
    const val CLOCK_WINDOW_MS = 120_000L

    const val KEY_BYTES = 32

    fun mac(domain: Domain, key: ByteArray, tsMs: Long, body: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "the handover key is $KEY_BYTES bytes" }
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(key, "HmacSHA256"))
        hmac.update(domain.context)
        hmac.update(tsMs.toString().toByteArray(Charsets.US_ASCII))
        hmac.update('\n'.code.toByte())
        hmac.update(body)
        return hmac.doFinal()
    }

    fun macBase64(domain: Domain, key: ByteArray, tsMs: Long, body: ByteArray): String =
        Base64.getEncoder().encodeToString(mac(domain, key, tsMs, body))

    /** Whether [macB64] is the [domain] MAC of [tsMs] and [body] under [key], compared in constant time. */
    fun verify(domain: Domain, key: ByteArray, tsMs: Long, body: ByteArray, macB64: String): Boolean {
        val got = runCatching { Base64.getDecoder().decode(macB64.trim()) }.getOrNull() ?: return false
        return MessageDigest.isEqual(got, mac(domain, key, tsMs, body))
    }
}

/** Why a take-back call was refused. */
enum class MacVerdict(val wire: String) {
    Accepted("ok"),
    Missing("missing"),
    Mismatch("bad_mac"),
    Clock("stale"),

    /** Signed by the box, but not `op: "yield"` from the box this phone took the uplink from. */
    NotAYield("not_yield"),
    Replay("replay"),
}

/**
 * Accepts each take-back call once: a good [UplinkMac.Domain.Yield] MAC, a
 * timestamp inside the clock window, a body that is `op: "yield"` naming the
 * box the phone took the uplink from, and a timestamp later than the last one
 * accepted, so a captured call cannot be played again.
 */
class YieldGuard {
    private var lastAcceptedMs: Long? = null

    @Synchronized
    fun check(key: ByteArray, tsMs: Long?, body: ByteArray, macB64: String?, boxDeviceId: String, nowMs: Long): MacVerdict {
        if (tsMs == null || macB64 == null) return MacVerdict.Missing
        if (!UplinkMac.verify(UplinkMac.Domain.Yield, key, tsMs, body, macB64)) return MacVerdict.Mismatch
        if (abs(tsMs - nowMs) > UplinkMac.CLOCK_WINDOW_MS) return MacVerdict.Clock
        if (!isYieldFrom(body, boxDeviceId)) return MacVerdict.NotAYield
        val last = lastAcceptedMs
        if (last != null && tsMs <= last) return MacVerdict.Replay
        lastAcceptedMs = tsMs
        return MacVerdict.Accepted
    }

    private fun isYieldFrom(body: ByteArray, boxDeviceId: String): Boolean {
        val obj = runCatching { Json.parseToJsonElement(body.decodeToString()) as? JsonObject }.getOrNull() ?: return false
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return str("op") == "yield" && str("box_device_id") == boxDeviceId
    }
}
