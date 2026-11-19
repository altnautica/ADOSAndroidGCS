package com.altnautica.gcs.radio.rx

import android.os.ParcelFileDescriptor

/**
 * Transmits one WFB link's radio port: each [send] becomes one wfb-ng packet
 * (FEC, session key and encryption by the vendored transmitter), written as a
 * record to the USB radio's transmit pipe.
 *
 * The parameters are the ground station's own, so the drone cannot tell this
 * transmitter from the box's: [key] is the fleet's 64-byte ground key (the
 * receive key, which carries both halves), the epoch is 0, and FEC, bandwidth
 * and MCS are what the caller passes from the box's table.
 *
 * Thread-safe: sends are serialised natively.
 */
class WfbTransmitter(
    pipe: ParcelFileDescriptor,
    key: ByteArray,
    val linkId: Int,
    val radioPort: Int,
    k: Int,
    n: Int,
    mcsIndex: Int,
    bandwidthMhz: Int,
) : AutoCloseable {

    @Volatile private var handle: Long

    init {
        WfbReceiver.loadLibrary()
        require(key.size == WfbReceiver.RX_KEY_BYTES) { "key must be ${WfbReceiver.RX_KEY_BYTES} bytes" }
        // The native side owns the descriptor from here (a duplicate, so the
        // caller's pipe stays usable for the other transmitter).
        handle = nativeOpen(pipe.dup().detachFd(), key, linkId, radioPort, k, n, mcsIndex, bandwidthMhz)
        check(handle != 0L) { "transmitter did not initialise" }
    }

    /** Sends one datagram; false when it is larger than one WFB packet carries or the transmitter is closed. */
    fun send(payload: ByteArray): Boolean {
        val h = handle
        return h != 0L && nativeSend(h, payload)
    }

    /** Cumulative counters since open. */
    fun stats(): WfbTxStats {
        val h = handle
        val v = if (h != 0L) nativeStats(h) else LongArray(STAT_FIELDS)
        return WfbTxStats(
            packetsIn = v[0],
            bytesIn = v[1],
            framesInjected = v[2],
            bytesInjected = v[3],
            framesDropped = v[4],
            packetsOversize = v[5],
        )
    }

    override fun close() {
        val h = synchronized(this) { handle.also { handle = 0L } }
        if (h != 0L) nativeClose(h)
    }

    companion object {
        private const val STAT_FIELDS = 6

        @JvmStatic private external fun nativeOpen(
            pipeFd: Int,
            key: ByteArray,
            linkId: Int,
            radioPort: Int,
            k: Int,
            n: Int,
            mcsIndex: Int,
            bandwidthMhz: Int,
        ): Long

        @JvmStatic private external fun nativeSend(handle: Long, payload: ByteArray): Boolean

        @JvmStatic private external fun nativeStats(handle: Long): LongArray

        @JvmStatic private external fun nativeClose(handle: Long)
    }
}

/** A transmitter's cumulative counters: datagrams in, frames written to the radio pipe, frames the full pipe dropped. */
data class WfbTxStats(
    val packetsIn: Long,
    val bytesIn: Long,
    val framesInjected: Long,
    val bytesInjected: Long,
    val framesDropped: Long,
    val packetsOversize: Long,
)
