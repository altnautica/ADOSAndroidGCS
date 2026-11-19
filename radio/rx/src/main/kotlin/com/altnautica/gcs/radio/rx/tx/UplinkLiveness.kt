package com.altnautica.gcs.radio.rx.tx

/**
 * What one evaluation of a transmitter's window concludes. The same verdicts
 * the box draws from its `wfb_tx` stats lines, so the two sides of a fleet
 * mean the same thing by "stalled".
 */
enum class TxVerdict {
    /** Bytes were offered and bytes reached the radio. */
    Healthy,

    /** Nothing was offered. Not a fault. */
    Idle,

    /** No counter sample arrived for the whole window: the radio service is not answering. */
    StalledSilent,

    /** Bytes were offered for the whole window and none reached the radio. */
    StalledNotInjecting,
    ;

    val isStall: Boolean get() = this == StalledSilent || this == StalledNotInjecting
}

/** One sample of a transmitter's cumulative counters, with the radio's. */
data class TxSample(
    /** Bytes offered to this transmitter (its datagrams, before FEC). */
    val bytesIn: Long,
    /** Bytes this transmitter wrote to the radio's transmit pipe. */
    val bytesWritten: Long,
    /** Bytes the radio handed to the chip, over both transmitters. */
    val radioBytesSent: Long,
)

/**
 * One transmitter's rolling liveness window over cumulative counters, judged
 * as the box judges its own (poll 5 s, window 30 s): no sample for the window
 * is a silent stall; ingress without bytes reaching the radio is a stall;
 * no ingress is idle. Every method takes the time it reasons about.
 */
class TxLivenessWindow(nowMs: Long, private val silenceMs: Long = SILENCE_WINDOW_MS) {
    private var lastSampleMs = nowMs
    private var windowStartMs = nowMs
    private var start: TxSample? = null
    private var latest: TxSample? = null

    fun observe(sample: TxSample, nowMs: Long) {
        lastSampleMs = nowMs
        if (start == null) start = sample
        latest = sample
    }

    /** The verdict, or null while the window is still filling. A non-stall verdict opens a new window. */
    fun evaluate(nowMs: Long): TxVerdict? {
        if (nowMs - lastSampleMs >= silenceMs) return TxVerdict.StalledSilent
        if (nowMs - windowStartMs < silenceMs) return null
        val a = start
        val b = latest
        val offered = if (a != null && b != null) b.bytesIn - a.bytesIn else 0L
        val reached = if (a != null && b != null) {
            minOf(b.bytesWritten - a.bytesWritten, b.radioBytesSent - a.radioBytesSent)
        } else {
            0L
        }
        val verdict = when {
            offered <= 0 -> TxVerdict.Idle
            reached <= 0 -> TxVerdict.StalledNotInjecting
            else -> TxVerdict.Healthy
        }
        if (!verdict.isStall) {
            windowStartMs = nowMs
            start = latest
        }
        return verdict
    }

    companion object {
        const val POLL_MS = 5_000L
        const val SILENCE_WINDOW_MS = 30_000L
    }
}

/** The phone's uplink as a surface may state it. Only [Active] may be shown as transmitting. */
enum class UplinkState {
    /** The drone acted on something the phone sent within the evidence window. */
    Active,

    /** Both transmitters are healthy or idle, with no drone-side evidence yet. */
    Unverified,

    /** A transmitter stalled, or the radio service was lost. */
    Down,
}

/**
 * The uplink state from the two transmitters' verdicts and drone-side
 * evidence: a HopAck the drone honoured, or an answer to a phone request.
 */
class UplinkJudge(private val evidenceWindowMs: Long = EVIDENCE_WINDOW_MS) {
    private var lastEvidenceMs: Long? = null
    private var control: TxVerdict? = null
    private var aux: TxVerdict? = null
    private var radioLost = false

    /** Records that the drone acted on something this phone sent. */
    fun evidence(nowMs: Long) {
        lastEvidenceMs = nowMs
    }

    /** Records window verdicts; a null (window still filling) keeps the transmitter's last one. */
    fun verdicts(control: TxVerdict?, aux: TxVerdict?) {
        if (control != null) this.control = control
        if (aux != null) this.aux = aux
    }

    fun radioLost() {
        radioLost = true
    }

    fun state(nowMs: Long): UplinkState {
        if (radioLost || control?.isStall == true || aux?.isStall == true) return UplinkState.Down
        val last = lastEvidenceMs
        return if (last != null && nowMs - last <= evidenceWindowMs) UplinkState.Active else UplinkState.Unverified
    }

    companion object {
        const val EVIDENCE_WINDOW_MS = 30_000L
    }
}
