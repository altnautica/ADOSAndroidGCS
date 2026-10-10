package com.altnautica.gcs.radio.rx

import java.nio.ByteBuffer

/**
 * Receives one WFB link (one transmitter): every frame record from the USB
 * radio goes to [feed]; datagrams recovered on any of [ports] come back
 * through [listener] on the calling thread, before [feed] returns.
 *
 * Not thread-safe: feed, [stats] and [close] belong to the frame-reader thread.
 */
class WfbReceiver(
    rxKey: ByteArray,
    val linkId: Int,
    ports: IntArray,
    private val listener: (port: Int, payload: ByteArray) -> Unit,
) : AutoCloseable {

    private var handle: Long

    init {
        loadLibrary()
        require(rxKey.size == RX_KEY_BYTES) { "rx key must be $RX_KEY_BYTES bytes" }
        handle = nativeCreate(rxKey, linkId, ports)
        check(handle != 0L) { "receiver did not initialise" }
    }

    /**
     * Feeds one record body: `u8 antennaCount | i8 rssiDbm x n | i8 snrDb x n | 802.11 frame`.
     * [frame] must be a direct buffer.
     */
    fun feed(frame: ByteBuffer, length: Int) {
        if (handle != 0L) nativeFeed(handle, frame, length)
    }

    /** Counters of the last closed one-second window, per port. */
    fun stats(): WfbStats = WfbStats.parse(if (handle != 0L) nativeStats(handle) else LongArray(0))

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeDestroy(h)
    }

    @Suppress("unused") // Called from native code.
    private fun onPacket(port: Int, payload: ByteArray) {
        listener(port, payload)
    }

    private external fun nativeCreate(rxKey: ByteArray, linkId: Int, ports: IntArray): Long

    private external fun nativeFeed(handle: Long, frame: ByteBuffer, length: Int)

    private external fun nativeStats(handle: Long): LongArray

    private external fun nativeDestroy(handle: Long)

    companion object {
        /** wfb-ng receive key: the receiver's secret key followed by the transmitter's public key. */
        const val RX_KEY_BYTES = 64

        @Volatile private var loaded = false

        fun loadLibrary() {
            if (loaded) return
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("ados_wfbrx")
                    loaded = true
                }
            }
        }

        /** A fresh X25519 key pair: secret (32 bytes) followed by public (32 bytes). */
        @JvmStatic external fun nativeGenerateKeyPair(): ByteArray?

        /** The X25519 public key of a 32-byte secret. */
        @JvmStatic external fun nativePublicKey(secret: ByteArray): ByteArray?

        /** Opens a sealed phone invite; null when it does not authenticate. */
        @JvmStatic external fun nativeOpenInvite(
            phoneSecret: ByteArray,
            gsEphPub: ByteArray,
            nonce: ByteArray,
            ciphertext: ByteArray,
            context: ByteArray,
        ): ByteArray?
    }
}

/** One second of receive counters for one radio port. */
data class WfbPortStats(
    val port: Int,
    val packets: Long,
    val decryptErrors: Long,
    val sessionPackets: Long,
    val dataPackets: Long,
    val uniquePackets: Long,
    val fecRecovered: Long,
    val lost: Long,
    val bad: Long,
    val delivered: Long,
)

data class WfbStats(
    val ports: List<WfbPortStats>,
    /** Frames that were not for this link (other links, other networks). */
    val foreignFrames: Long,
    val malformedRecords: Long,
) {
    companion object {
        private const val FIELDS = 10

        fun parse(raw: LongArray): WfbStats {
            if (raw.size < 2) return WfbStats(emptyList(), 0, 0)
            val portFields = raw.size - 2
            val ports = (0 until portFields / FIELDS).map { i ->
                val o = i * FIELDS
                WfbPortStats(
                    port = raw[o].toInt(),
                    packets = raw[o + 1],
                    decryptErrors = raw[o + 2],
                    sessionPackets = raw[o + 3],
                    dataPackets = raw[o + 4],
                    uniquePackets = raw[o + 5],
                    fecRecovered = raw[o + 6],
                    lost = raw[o + 7],
                    bad = raw[o + 8],
                    delivered = raw[o + 9],
                )
            }
            return WfbStats(ports, raw[raw.size - 2], raw[raw.size - 1])
        }
    }
}
