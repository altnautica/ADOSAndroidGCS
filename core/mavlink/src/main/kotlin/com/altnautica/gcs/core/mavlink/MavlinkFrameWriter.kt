package com.altnautica.gcs.core.mavlink

import com.altnautica.gcs.core.mavlink.MavlinkConstants.HEADER_V2
import com.altnautica.gcs.core.mavlink.MavlinkConstants.INCOMPAT_SIGNED
import com.altnautica.gcs.core.mavlink.MavlinkConstants.SIGNATURE_LEN
import com.altnautica.gcs.core.mavlink.MavlinkConstants.STX_V2
import com.altnautica.gcs.core.mavlink.messages.MavCrcExtra
import com.altnautica.gcs.core.mavlink.messages.MavMessage
import java.security.MessageDigest

/**
 * MAVLink 2 message signing state for one link.
 *
 * [secretKey] is the 32-byte shared secret. Timestamps are 10 µs units since
 * 2015-01-01 UTC and strictly increase per link, as the protocol requires.
 */
class MavlinkSigning(
    secretKey: ByteArray,
    val linkId: Int,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) {
    private val key = secretKey.copyOf()
    private var lastTimestamp = 0L

    init {
        require(key.size == 32) { "MAVLink signing key must be 32 bytes" }
        require(linkId in 0..255) { "linkId must fit in one byte" }
    }

    @Synchronized
    internal fun nextTimestamp(): Long {
        val now = (wallClockMs() - EPOCH_2015_MS) * 100
        lastTimestamp = maxOf(now, lastTimestamp + 1)
        return lastTimestamp
    }

    /** Signature block (link id, 48-bit timestamp, first 6 bytes of the SHA-256) for [frame] up to its CRC. */
    internal fun sign(frame: ByteArray, frameLen: Int, timestamp: Long): ByteArray {
        val block = ByteArray(SIGNATURE_LEN)
        block[0] = linkId.toByte()
        for (i in 0 until 6) block[1 + i] = (timestamp ushr (8 * i)).toByte()
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(key)
        sha.update(frame, 0, frameLen)
        sha.update(block, 0, 7)
        val digest = sha.digest()
        System.arraycopy(digest, 0, block, 7, 6)
        return block
    }

    companion object {
        /** 2015-01-01T00:00:00Z in Unix milliseconds. */
        const val EPOCH_2015_MS: Long = 1_420_070_400_000L
    }
}

/**
 * Encodes MAVLink 2 frames for one link with its own sequence counter.
 * Defaults identify this app as a ground station (sysid 255, compid 190).
 */
class MavlinkFrameWriter(
    val sysId: Int = MavlinkConstants.GCS_SYSTEM_ID,
    val compId: Int = MavlinkConstants.GCS_COMPONENT_ID,
    @Volatile var signing: MavlinkSigning? = null,
) {
    private var seq = 0

    fun encode(message: MavMessage): ByteArray = encode(message.msgId, message.encodePayload())

    @Synchronized
    fun encode(msgId: Int, payload: ByteArray): ByteArray {
        val crcExtra = MavCrcExtra.crcExtra(msgId)
        require(crcExtra >= 0) { "Unknown MAVLink message id $msgId" }
        require(payload.size <= 255) { "Payload too long" }
        val signer = signing
        val total = HEADER_V2 + payload.size + 2 + if (signer != null) SIGNATURE_LEN else 0
        val out = ByteArray(total)
        out[0] = STX_V2.toByte()
        out[1] = payload.size.toByte()
        out[2] = (if (signer != null) INCOMPAT_SIGNED else 0).toByte()
        out[3] = 0
        out[4] = seq.toByte()
        out[5] = sysId.toByte()
        out[6] = compId.toByte()
        out[7] = msgId.toByte()
        out[8] = (msgId shr 8).toByte()
        out[9] = (msgId shr 16).toByte()
        System.arraycopy(payload, 0, out, HEADER_V2, payload.size)
        var crc = MavCrc.accumulate(MavCrc.SEED, out, 1, HEADER_V2 - 1 + payload.size)
        crc = MavCrc.accumulate(crc, crcExtra)
        val crcAt = HEADER_V2 + payload.size
        out[crcAt] = crc.toByte()
        out[crcAt + 1] = (crc shr 8).toByte()
        if (signer != null) {
            val block = signer.sign(out, crcAt + 2, signer.nextTimestamp())
            System.arraycopy(block, 0, out, crcAt + 2, SIGNATURE_LEN)
        }
        seq = (seq + 1) and 0xFF
        return out
    }
}
