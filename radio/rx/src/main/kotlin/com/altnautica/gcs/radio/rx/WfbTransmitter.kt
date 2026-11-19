package com.altnautica.gcs.radio.rx

import android.os.ParcelFileDescriptor
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

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
 * Thread-safe: sends are serialised natively, and [close] waits for every
 * send and stats read already inside the native transmitter before it frees
 * it; once closed, both answer without reaching native code.
 */
class WfbTransmitter internal constructor(
    private val native: WfbTxNative,
    handle: Long,
    val linkId: Int,
    val radioPort: Int,
) : AutoCloseable {

    constructor(
        pipe: ParcelFileDescriptor,
        key: ByteArray,
        linkId: Int,
        radioPort: Int,
        k: Int,
        n: Int,
        mcsIndex: Int,
        bandwidthMhz: Int,
    ) : this(Jni, open(pipe, key, linkId, radioPort, k, n, mcsIndex, bandwidthMhz), linkId, radioPort)

    /** Native calls hold the read side; [close] holds the write side. */
    private val lock = ReentrantReadWriteLock()

    /** The native transmitter; 0 once closed. Guarded by [lock]. */
    private var handle: Long = handle

    /** Sends one datagram; false when it is larger than one WFB packet carries or the transmitter is closed. */
    fun send(payload: ByteArray): Boolean = lock.read {
        handle != 0L && native.send(handle, payload)
    }

    /** Cumulative counters since open; zeros once closed. */
    fun stats(): WfbTxStats {
        val v = lock.read { if (handle != 0L) native.stats(handle) else LongArray(STAT_FIELDS) }
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
        lock.write {
            val h = handle
            handle = 0L
            if (h != 0L) native.close(h)
        }
    }

    companion object {
        private const val STAT_FIELDS = 6

        // The native side owns the descriptor from here (a duplicate, so the
        // caller's pipe stays usable for the other transmitter).
        private fun open(
            pipe: ParcelFileDescriptor,
            key: ByteArray,
            linkId: Int,
            radioPort: Int,
            k: Int,
            n: Int,
            mcsIndex: Int,
            bandwidthMhz: Int,
        ): Long {
            WfbReceiver.loadLibrary()
            require(key.size == WfbReceiver.RX_KEY_BYTES) { "key must be ${WfbReceiver.RX_KEY_BYTES} bytes" }
            val handle = nativeOpen(pipe.dup().detachFd(), key, linkId, radioPort, k, n, mcsIndex, bandwidthMhz)
            check(handle != 0L) { "transmitter did not initialise" }
            return handle
        }

        private object Jni : WfbTxNative {
            override fun send(handle: Long, payload: ByteArray): Boolean = nativeSend(handle, payload)
            override fun stats(handle: Long): LongArray = nativeStats(handle)
            override fun close(handle: Long) = nativeClose(handle)
        }

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

/** The native transmitter's calls on an open handle: the JNI bridge, or a stand-in under test. */
internal interface WfbTxNative {
    fun send(handle: Long, payload: ByteArray): Boolean
    fun stats(handle: Long): LongArray
    fun close(handle: Long)
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
