package com.altnautica.gcs.radio.rx.hop

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A drone's channel-hop announcement, 51 bytes on its control plane:
 *
 * ```
 * [0..8)   magic "AD05HOP1"
 * [8]      version 3
 * [9..17)  delay_ms, u64 big-endian: milliseconds from this frame to the flip
 * [17]     target channel
 * [18]     trigger (0 periodic, 1 reactive, 2 manual)
 * [19..51) HMAC-SHA256 of bytes [0..19) under the hop pair key
 * ```
 *
 * The delay is relative to the frame, so each side schedules the flip
 * against its own monotonic clock.
 */
data class HopAnnounce(val delayMs: Long, val targetChannel: Int, val trigger: HopTrigger) {

    companion object {
        const val LENGTH = 51
        const val VERSION = 3
        private val MAGIC = "AD05HOP1".toByteArray(Charsets.US_ASCII)
        private const val SIGNED_LEN = 19

        /** Verifies length, magic, version and HMAC, then decodes; null when any check fails. */
        fun parse(packet: ByteArray, pairKey: ByteArray): HopAnnounce? {
            if (!verify(packet, pairKey)) return null
            val delay = ByteBuffer.wrap(packet, 9, 8).long
            val trigger = HopTrigger.fromByte(packet[18].toInt() and 0xFF)
            return HopAnnounce(delay, packet[17].toInt() and 0xFF, trigger)
        }

        fun verify(packet: ByteArray, pairKey: ByteArray): Boolean {
            if (packet.size != LENGTH) return false
            for (i in MAGIC.indices) if (packet[i] != MAGIC[i]) return false
            if (packet[8].toInt() != VERSION) return false
            val expected = sign(packet.copyOfRange(0, SIGNED_LEN), pairKey)
            return MessageDigest.isEqual(expected, packet.copyOfRange(SIGNED_LEN, LENGTH))
        }

        /** Builds a signed announcement (used by tests and by a future transmit path). */
        fun build(delayMs: Long, targetChannel: Int, trigger: HopTrigger, pairKey: ByteArray): ByteArray {
            val pkt = ByteArray(LENGTH)
            MAGIC.copyInto(pkt)
            pkt[8] = VERSION.toByte()
            ByteBuffer.wrap(pkt, 9, 8).putLong(delayMs)
            pkt[17] = targetChannel.toByte()
            pkt[18] = trigger.wire.toByte()
            sign(pkt.copyOfRange(0, SIGNED_LEN), pairKey).copyInto(pkt, SIGNED_LEN)
            return pkt
        }

        /**
         * The hop pair key: SHA-256 of `"ados/wfb/hop/v2\n" || drone.key`, or of
         * the cold-start label on a pair that has never been bound. An invite
         * carries the derived key, so the phone never holds `drone.key`.
         */
        fun derivePairKey(droneKey: ByteArray?): ByteArray {
            val sha = MessageDigest.getInstance("SHA-256")
            if (droneKey != null) {
                sha.update("ados/wfb/hop/v2\n".toByteArray(Charsets.US_ASCII))
                sha.update(droneKey)
            } else {
                sha.update("ados/wfb/hop/v2/cold-start".toByteArray(Charsets.US_ASCII))
            }
            return sha.digest()
        }

        private fun sign(data: ByteArray, key: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }
    }
}

enum class HopTrigger(val wire: Int) {
    PERIODIC(0),
    REACTIVE(1),
    MANUAL(2),
    ;

    companion object {
        /** An unknown trigger byte reads as periodic, the conservative default. */
        fun fromByte(value: Int): HopTrigger = entries.firstOrNull { it.wire == value } ?: PERIODIC
    }
}
