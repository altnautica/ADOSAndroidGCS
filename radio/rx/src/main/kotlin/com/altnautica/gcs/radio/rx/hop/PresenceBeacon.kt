package com.altnautica.gcs.radio.rx.hop

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A ground's presence beacon, 68 bytes on the control plane every 10 s. The
 * drone's link gate needs it (with HopAcks) to keep counting this ground as
 * its linked peer; after 25 s without one it falls back to the home channel.
 *
 * ```
 * [0..8)   magic "AD05PRES"
 * [8]      version 1
 * [9..25)  device id, UTF-8, cut to 16 bytes, zero-padded
 * [25]     role: 0x01 drone, 0x02 ground
 * [26]     current channel
 * [27]     RSSI dBm (i8; 0 when unknown, as the box sends)
 * [28..36) epoch milliseconds, u64 big-endian
 * [36..68) HMAC-SHA256 of bytes [0..36) under the hop pair key
 * ```
 */
object PresenceBeacon {
    const val LENGTH = 68
    const val INTERVAL_MS = 10_000L
    private const val VERSION = 1
    const val ROLE_DRONE = 0x01
    const val ROLE_GROUND = 0x02
    private const val ID_BYTES = 16
    private const val SIGNED_LEN = 36
    private val MAGIC = "AD05PRES".toByteArray(Charsets.US_ASCII)

    /** A verified beacon: who sent it, as which role, on which channel. */
    data class Parsed(val deviceId: String, val role: Int, val channel: Int)

    /** A ground beacon for [deviceId] on [channel], stamped [epochMs]. */
    fun build(deviceId: String, channel: Int, epochMs: Long, pairKey: ByteArray): ByteArray {
        val pkt = ByteArray(LENGTH)
        MAGIC.copyInto(pkt)
        pkt[8] = VERSION.toByte()
        val id = deviceId.toByteArray(Charsets.UTF_8)
        id.copyInto(pkt, 9, 0, minOf(id.size, ID_BYTES))
        pkt[25] = ROLE_GROUND.toByte()
        pkt[26] = channel.toByte()
        pkt[27] = 0
        ByteBuffer.wrap(pkt, 28, 8).putLong(epochMs)
        sign(pkt, pairKey).copyInto(pkt, SIGNED_LEN)
        return pkt
    }

    /**
     * Verifies [pkt] under [pairKey] and reads it, as the agent's
     * `parse_presence_beacon` does; null on a wrong length, magic or MAC.
     */
    fun parse(pkt: ByteArray, pairKey: ByteArray): Parsed? {
        if (pkt.size != LENGTH) return null
        if (!pkt.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val mac = pkt.copyOfRange(SIGNED_LEN, LENGTH)
        if (!MessageDigest.isEqual(sign(pkt, pairKey), mac)) return null
        var end = 9
        while (end < 9 + ID_BYTES && pkt[end] != 0.toByte()) end++
        return Parsed(
            deviceId = String(pkt, 9, end - 9, Charsets.UTF_8),
            role = pkt[25].toInt() and 0xff,
            channel = pkt[26].toInt() and 0xff,
        )
    }

    private fun sign(pkt: ByteArray, pairKey: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairKey, "HmacSHA256"))
        mac.update(pkt, 0, SIGNED_LEN)
        return mac.doFinal()
    }
}
