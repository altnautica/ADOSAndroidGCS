package com.altnautica.gcs.radio.rx.bind

import android.os.SystemClock
import android.util.Log
import com.altnautica.gcs.radio.rx.WfbReceiver
import com.altnautica.gcs.radio.rx.WfbTransmitter
import com.altnautica.gcs.radio.rx.usb.FrameRecord
import com.altnautica.gcs.radio.rx.usb.FrameRecordReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Where a running bind is. */
enum class BindStage { PreparingKeys, OpeningRadio, WaitingForDrone, Exchanging, Storing }

/** How a bind ended on the air. */
sealed interface BindOutcome {
    data class Paired(val droneVersion: String) : BindOutcome
    data class Failed(val reason: String) : BindOutcome
}

/**
 * One over-the-air bind on an open [BindRadio]: the bind planes (a
 * transmitter on stream 0xff and a receiver on 0x7f, both under the shared
 * bind key and the `drone_bind` link id), the tunnel's IP endpoint between
 * them, a TCP connection to the drone's bind server, and the exchange that
 * hands it [droneKey]. Paired only with peer proof: frames decoded on the
 * receive plane during this session and the drone's `OK`.
 *
 * Every tunnel call runs on [bindThread], a single-threaded dispatcher.
 */
class BindClient(
    private val bindKey: ByteArray,
    private val droneKey: ByteArray,
    private val bindThread: CoroutineDispatcher,
    /** The stage, and whether anything has been decoded on the bind receive plane yet. */
    private val onStage: (BindStage, Boolean) -> Unit,
) {
    /** Datagrams decoded on the bind receive plane this session (pings included). */
    val framesDecoded = AtomicLong()

    suspend fun run(radio: BindRadio): BindOutcome {
        val tx = radio.radio.txFrames().use { pipe ->
            WfbTransmitter(
                pipe, bindKey, BindProfile.LINK_ID, BindProfile.TX_PORT,
                BindProfile.FEC_K, BindProfile.FEC_N, BindProfile.MCS, BindProfile.BANDWIDTH_MHZ,
            )
        }
        val inbound = ConcurrentLinkedQueue<ByteArray>()
        val receiver = WfbReceiver(bindKey, BindProfile.LINK_ID, intArrayOf(BindProfile.RX_PORT)) { _, payload ->
            framesDecoded.incrementAndGet()
            inbound.add(payload)
        }
        val reader = Thread({ readLoop(radio, receiver) }, "phone-bind-rx").apply { start() }
        try {
            return withContext(bindThread) { overTunnel(tx, inbound) }
        } finally {
            tx.close()
            // Closing the radio ends the frame pipe; the reader then releases the receiver.
            runCatching { radio.radio.close() }
            reader.join(READER_JOIN_MS)
        }
    }

    private suspend fun overTunnel(tx: WfbTransmitter, inbound: ConcurrentLinkedQueue<ByteArray>): BindOutcome = coroutineScope {
        var stage = BindStage.WaitingForDrone
        var heard = false
        fun report(next: BindStage) {
            stage = next
            onStage(stage, heard)
        }
        BindTunnel().use { tunnel ->
            val pump = launch {
                while (isActive) {
                    while (true) tunnel.input(inbound.poll() ?: break)
                    for (d in tunnel.poll()) tx.send(d)
                    if (!heard && framesDecoded.get() > 0) {
                        heard = true
                        onStage(stage, true)
                    }
                    delay(PUMP_MS)
                }
            }
            try {
                report(BindStage.WaitingForDrone)
                if (!connect(tunnel)) return@coroutineScope BindOutcome.Failed(NO_DRONE)
                report(BindStage.Exchanging)
                val result = BindExchange().run(TunnelStream(tunnel), BindBundle.encode(droneKey))
                val frames = framesDecoded.get()
                Log.i(TAG, "bind exchange: $result, frames decoded $frames")
                when {
                    PeerProof.paired(frames, result) -> BindOutcome.Paired((result as ExchangeResult.Accepted).droneVersion)
                    result is ExchangeResult.Refused -> BindOutcome.Failed("The drone refused the key: ${result.reason}")
                    frames == 0L -> BindOutcome.Failed(NO_DRONE)
                    else -> BindOutcome.Failed("The drone stopped answering during ${(result as? ExchangeResult.NoAnswer)?.step ?: "the bind"}")
                }
            } finally {
                pump.cancel()
            }
        }
    }

    /** Connects to the drone's bind server, retrying while it is not listening yet, within [CONNECT_BUDGET_MS]. */
    private suspend fun connect(tunnel: BindTunnel): Boolean {
        val deadline = SystemClock.elapsedRealtime() + CONNECT_BUDGET_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (tunnel.state != TunnelTcpState.Connecting && !tunnel.connect(BindProfile.DRONE_IP, BindProfile.BIND_TCP_PORT)) {
                delay(RETRY_MS)
                continue
            }
            while (tunnel.state == TunnelTcpState.Connecting && SystemClock.elapsedRealtime() < deadline) delay(PUMP_MS)
            when (tunnel.state) {
                TunnelTcpState.Connected -> return true
                // Refused (no listener yet) or reset: try again shortly.
                else -> delay(RETRY_MS)
            }
        }
        return false
    }

    private fun readLoop(radio: BindRadio, receiver: WfbReceiver) {
        try {
            FileInputStream(radio.frames.fileDescriptor).use { input ->
                val records = FrameRecordReader(input)
                while (true) {
                    val len = records.next()
                    if (len < 0) break
                    val body = records.buffer
                    if (FrameRecord.linkId(body, len) == BindProfile.LINK_ID) receiver.feed(body, len)
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "bind frame pipe ended", e)
        } finally {
            receiver.close()
        }
    }

    /** The TCP connection as a line stream. */
    private class TunnelStream(private val tunnel: BindTunnel) : BindStream {
        private val splitter = LineSplitter()
        private val lines = ArrayDeque<String>()

        override suspend fun write(bytes: ByteArray) {
            var off = 0
            while (off < bytes.size) {
                if (tunnel.state != TunnelTcpState.Connected) throw IOException("bind connection closed")
                off += tunnel.send(bytes, off, bytes.size - off)
                if (off < bytes.size) delay(PUMP_MS)
            }
        }

        override suspend fun readLine(timeoutMs: Long): String? {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (coroutineContext.isActive) {
                lines.addAll(splitter.push(tunnel.take()))
                lines.removeFirstOrNull()?.let { return it }
                if (tunnel.state != TunnelTcpState.Connected || SystemClock.elapsedRealtime() >= deadline) return null
                delay(PUMP_MS)
            }
            return null
        }
    }

    companion object {
        private const val TAG = "AdosPhoneBind"
        const val NO_DRONE = "No drone answered on the bind channel"

        /** The agent's own key-transfer budget. */
        const val CONNECT_BUDGET_MS = 90_000L
        private const val RETRY_MS = 1_000L
        private const val PUMP_MS = 10L
        private const val READER_JOIN_MS = 2_000L
    }
}
