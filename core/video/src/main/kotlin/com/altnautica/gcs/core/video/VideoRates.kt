package com.altnautica.gcs.core.video

import com.altnautica.gcs.core.video.whep.InboundVideoStats
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One second of a WebRTC video session, in frames per second at each hop:
 * - [receivedFps]: whole frames the RTP receiver assembled;
 * - [decodedFps] / [droppedFps]: what the stack reports decoding and dropping;
 * - [deliveredFps]: frames that reached the session's sink (`FrameFanout.decoded`);
 * - [shownFps]: frames handed to a renderer on screen (`FrameFanout.shown`).
 * Rates the stack does not report are null.
 */
data class VideoRates(
    val receivedFps: Double?,
    val decodedFps: Double?,
    val droppedFps: Double?,
    val deliveredFps: Double,
    val shownFps: Double,
    val interFrameMs: Double?,
    val decoder: String?,
    val latencyMs: Int?,
) {
    /**
     * Share of the frames the stack received and kept that never reached the sink: frames
     * lost between the decoder and the app, which the stack's own counters do not show.
     * Null below [MIN_FPS], where one late frame would swing it.
     */
    val handoffLoss: Double?
        get() {
            val kept = (receivedFps ?: return null) - (droppedFps ?: 0.0)
            if (kept < MIN_FPS) return null
            return ((kept - deliveredFps) / kept).coerceIn(0.0, 1.0)
        }

    fun logLine(): String = String.format(
        Locale.ROOT,
        "received_fps=%s decoded_fps=%s dropped_fps=%s delivered_fps=%.1f shown_fps=%.1f interframe_ms=%s latency_ms=%s decoder=%s",
        receivedFps.fmt(), decodedFps.fmt(), droppedFps.fmt(), deliveredFps, shownFps, interFrameMs.fmt(), latencyMs, decoder,
    )

    companion object {
        const val MIN_FPS = 5.0

        private fun Double?.fmt() = this?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"
    }
}

/** Turns consecutive cumulative samples into [VideoRates]; the first sample after [reset] gives none. */
class VideoStatsMeter {
    private class Sample(val atMs: Long, val stats: InboundVideoStats?, val delivered: Long, val shown: Long)

    private var last: Sample? = null

    fun update(nowMs: Long, stats: InboundVideoStats?, delivered: Long, shown: Long): VideoRates? {
        val prev = last
        last = Sample(nowMs, stats, delivered, shown)
        if (prev == null || nowMs <= prev.atMs) return null
        val seconds = (nowMs - prev.atMs) / 1_000.0
        val was = prev.stats
        fun delta(now: Long?, before: Long?): Long? = if (now != null && before != null && now >= before) now - before else null
        fun rate(now: Long?, before: Long?): Double? = delta(now, before)?.let { it / seconds }
        val decoded = delta(stats?.framesDecoded, was?.framesDecoded)
        val interFrame = stats?.totalInterFrameDelayS?.let { total ->
            val before = was?.totalInterFrameDelayS ?: return@let null
            if (decoded == null || decoded <= 0) null else (total - before) / decoded * 1_000
        }
        val emitted = delta(stats?.jitterBufferEmittedCount, was?.jitterBufferEmittedCount)
        val latency = stats?.jitterBufferDelayS?.let { total ->
            val before = was?.jitterBufferDelayS ?: return@let null
            if (emitted == null || emitted <= 0) null else ((total - before) / emitted * 1_000).roundToInt()
        }
        return VideoRates(
            receivedFps = rate(stats?.framesReceived, was?.framesReceived),
            decodedFps = decoded?.let { it / seconds },
            droppedFps = rate(stats?.framesDropped, was?.framesDropped),
            deliveredFps = (delivered - prev.delivered).coerceAtLeast(0) / seconds,
            shownFps = (shown - prev.shown).coerceAtLeast(0) / seconds,
            interFrameMs = interFrame,
            decoder = stats?.decoderImplementation,
            latencyMs = latency,
        )
    }

    fun reset() {
        last = null
    }
}

/**
 * Says when frames have been lost between decoder and sink ([VideoRates.handoffLoss] at or
 * above [threshold]) for [seconds] seconds in a row.
 */
class HandoffLossMonitor(
    private val threshold: Double = 0.15,
    private val seconds: Int = 5,
) {
    private var run = 0

    fun onRates(rates: VideoRates?): Boolean {
        val loss = rates?.handoffLoss
        run = if (loss != null && loss >= threshold) run + 1 else 0
        return run >= seconds
    }

    fun reset() {
        run = 0
    }
}
