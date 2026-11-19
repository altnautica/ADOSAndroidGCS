package com.altnautica.gcs.radio.rx.bench

import android.os.ParcelFileDescriptor
import android.util.Log
import com.altnautica.gcs.radio.usb.IUsbRadio
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * A radio that is the fleet bench's emulated air instead of a USB dongle, for
 * the phone-ground bench scenario. Debug builds only.
 *
 * It answers the same calls as the USB radio service, so the session above it
 * runs unchanged: [frames] delivers what the relay sends this node as receive
 * records, and every record written to [txFrames] goes to the relay under the
 * channel its 802.11 header names. [open] registers one receiving lane per
 * channel the session's receivers listen on, and [addTxChannel] the channels
 * the phone transmits on, with the bench relay.
 */
class BenchRelayRadio(private val config: BenchRelayConfig, private val rxChannels: List<Long>, txChannels: List<Long>) :
    IUsbRadio.Stub() {

    private val txLanes = txChannels.toList()
    @Volatile private var channel = 0
    @Volatile private var bandwidth = 20
    @Volatile private var closed = false
    private var rxPipe: Array<ParcelFileDescriptor>? = null
    private var txPipe: Array<ParcelFileDescriptor>? = null
    private var downlink: DatagramSocket? = null
    private val threads = mutableListOf<Thread>()

    private val rxFrames = AtomicLong()
    private val rxDropped = AtomicLong()
    private val txOffered = AtomicLong()
    private val txSent = AtomicLong()
    private val txRefused = AtomicLong()
    private val txBytesOffered = AtomicLong()
    private val txBytesSent = AtomicLong()

    override fun open(usbFd: ParcelFileDescriptor?, vendorId: Int, productId: Int) {
        usbFd?.close()
        for (ch in rxChannels) register(ch, "rx")
        for (ch in txLanes) register(ch, "tx")
        downlink = DatagramSocket(InetSocketAddress(config.listenPort))
    }

    override fun setChannel(channel: Int, bandwidthMhz: Int) {
        // The emulated air has no channels; the session's hop bookkeeping still runs.
        this.channel = channel
        bandwidth = bandwidthMhz
    }

    override fun frames(): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        rxPipe = pipe
        val socket = requireNotNull(downlink) { "frames() before open()" }
        threads += Thread({ receiveLoop(socket, pipe[1]) }, "bench-relay-rx").apply { start() }
        return pipe[0]
    }

    override fun txFrames(): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        txPipe = pipe
        threads += Thread({ transmitLoop(pipe[0]) }, "bench-relay-tx").apply { start() }
        return pipe[1]
    }

    override fun stats(): LongArray =
        longArrayOf(if (closed) 0 else 1, rxFrames.get(), rxFrames.get(), rxDropped.get(), 0, channel.toLong())

    override fun txStats(): LongArray =
        longArrayOf(txOffered.get(), txSent.get(), txRefused.get(), txBytesOffered.get(), txBytesSent.get())

    override fun close() {
        if (closed) return
        closed = true
        downlink?.close()
        // Closing our ends ends both loops; the session closes its own ends.
        rxPipe?.get(1)?.close()
        txPipe?.get(0)?.close()
        threads.forEach { it.join(JOIN_MS) }
    }

    private fun register(channel: Long, direction: String) {
        val line = """{"op":"register","node":"${config.node}","channel":$channel,"direction":"$direction","addr":"${config.registerAddress}"}""" + "\n"
        Socket().use { s ->
            s.connect(InetSocketAddress(config.relayHost, CONTROL_PORT), CONNECT_MS)
            s.getOutputStream().write(line.toByteArray(Charsets.UTF_8))
        }
    }

    private fun receiveLoop(socket: DatagramSocket, writeEnd: ParcelFileDescriptor) {
        val buf = ByteArray(MAX_DATAGRAM)
        var seq = 0
        try {
            FileOutputStream(writeEnd.fileDescriptor).use { out ->
                while (!closed) {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val body = BenchAirCodec.toRxRecord(buf, p.length, seq) ?: continue
                    seq = (seq + 16) and 0xFFFF
                    if (body.size > MAX_RECORD) {
                        rxDropped.incrementAndGet()
                        continue
                    }
                    val record = ByteArray(4 + body.size)
                    record[0] = body.size.toByte()
                    record[1] = (body.size shr 8).toByte()
                    body.copyInto(record, 4)
                    out.write(record)
                    rxFrames.incrementAndGet()
                }
            }
        } catch (e: IOException) {
            if (!closed) Log.w(TAG, "bench relay receive ended", e)
        }
    }

    private fun transmitLoop(readEnd: ParcelFileDescriptor) {
        val out = DatagramSocket()
        val relay = InetSocketAddress(config.relayHost, DATA_PORT)
        try {
            DataInputStream(FileInputStream(readEnd.fileDescriptor)).use { input ->
                val len = ByteArray(4)
                while (!closed) {
                    input.readFully(len)
                    val n = (len[0].toInt() and 0xFF) or ((len[1].toInt() and 0xFF) shl 8) or
                        ((len[2].toInt() and 0xFF) shl 16) or ((len[3].toInt() and 0xFF) shl 24)
                    if (n !in 1..MAX_RECORD) throw IOException("transmit record length $n out of range")
                    val record = ByteArray(n)
                    input.readFully(record)
                    txOffered.incrementAndGet()
                    txBytesOffered.addAndGet(n.toLong())
                    val (ch, packet) = BenchAirCodec.fromTxRecord(record) ?: run {
                        txRefused.incrementAndGet()
                        null
                    } ?: continue
                    val fwd = BenchAirCodec.fwdHeader(mcs = 1, bandwidthMhz = bandwidth, freqMhz = 5000 + 5 * channel)
                    val datagram = BenchAirCodec.toRelay(ch, config.node, fwd, packet)
                    out.send(DatagramPacket(datagram, datagram.size, relay))
                    txSent.incrementAndGet()
                    txBytesSent.addAndGet(n.toLong())
                }
            }
        } catch (_: EOFException) {
            // The session closed its write end.
        } catch (e: IOException) {
            if (!closed) Log.w(TAG, "bench relay transmit ended", e)
        } finally {
            out.close()
        }
    }

    private companion object {
        const val TAG = "AdosBenchRelay"
        const val CONTROL_PORT = 15800
        const val DATA_PORT = 15801
        const val CONNECT_MS = 5_000
        const val JOIN_MS = 2_000L
        const val MAX_DATAGRAM = 65_536
        const val MAX_RECORD = 4096
    }
}
