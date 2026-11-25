package com.altnautica.gcs.core.video.whep

import android.util.Log
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.FrameFanout
import com.altnautica.gcs.core.video.FrameWatchdog
import com.altnautica.gcs.core.video.HandoffLossMonitor
import com.altnautica.gcs.core.video.RedialBackoff
import com.altnautica.gcs.core.video.VideoClock
import com.altnautica.gcs.core.video.VideoOutput
import com.altnautica.gcs.core.video.VideoRates
import com.altnautica.gcs.core.video.VideoRecording
import com.altnautica.gcs.core.video.VideoSessionHandle
import com.altnautica.gcs.core.video.VideoStatsMeter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import org.webrtc.EglBase

/**
 * One WHEP leg with its re-dial policy:
 * - connection `disconnected` gets [DISCONNECT_GRACE_MS] to recover, then re-dial;
 * - `failed`, a refused offer, or ICE not connected within [CONNECT_TIMEOUT_MS] re-dials
 *   with 0.5→8 s backoff;
 * - connected but no first frame within [FIRST_FRAME_AFTER_CONNECT_MS], or no frame for
 *   2.5 s once flowing, marks the leg frozen and re-dials once at once; a second freeze
 *   before frames flow again backs off like a failure;
 * - no first frame within [FIRST_FRAME_TIMEOUT_MS] of the dial fails it;
 * - [VideoState.Live] only while frames reach a renderer on screen;
 * - once a second the peer's counters become [rates], logged under `VideoStats`; when
 *   frames go missing between decoder and sink for 5 s in a row, [onHandoffLoss] may move
 *   the decoder to another output, and the session re-dials at once to take it.
 * Every session resource is DELETEd before the next dial and on [stop].
 */
class WhepSession(
    override val source: VideoSource.AgentWhep,
    private val scope: CoroutineScope,
    private val signaling: WhepSignaling,
    private val peerFactory: WhepPeerFactory,
    private val apiKey: suspend () -> String?,
    eglContext: () -> EglBase.Context,
    private val clock: VideoClock,
    override val recording: VideoRecording?,
    private val onHandoffLoss: () -> Boolean = { false },
) : VideoSessionHandle {

    private val _state = MutableStateFlow<VideoState>(VideoState.Idle)
    override val state: StateFlow<VideoState> = _state

    private val _rates = MutableStateFlow<VideoRates?>(null)
    override val rates: StateFlow<VideoRates?> = _rates

    private val frames = FrameFanout(clock)
    private val backoff = RedialBackoff(initialMs = 500L, maxMs = 8_000L)

    override val output: VideoOutput = frames.output(eglContext)

    private var job: Job? = null
    private var peer: WhepPeer? = null
    private var resource: HttpUrl? = null
    private var key: String? = null

    override fun start() {
        if (job != null) return
        job = scope.launch { run() }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        withContext(NonCancellable) { teardown() }
        frames.clear()
        _state.value = VideoState.Idle
        _rates.value = null
    }

    private suspend fun run() {
        var freezeRedialUsed = false
        while (scope.isActive) {
            _state.value = VideoState.Connecting
            Log.i(LINK_TAG, "whep_dial base=${source.baseUrl} stream=${source.streamId}")
            val dialed = try {
                dial()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = VideoState.Failed(e.message ?: "WHEP dial failed")
                false
            }
            val outcome = if (dialed) monitor() else Outcome.Failed
            if (outcome == Outcome.Flowed || outcome == Outcome.Redecode) freezeRedialUsed = false
            teardown()
            when (outcome) {
                Outcome.Frozen -> if (!freezeRedialUsed) {
                    freezeRedialUsed = true
                    continue
                } else {
                    delay(backoff.nextDelayMs())
                }
                Outcome.Lost, Outcome.Flowed, Outcome.Redecode -> Unit
                Outcome.Failed -> {
                    if (_state.value !is VideoState.Failed) _state.value = VideoState.Failed("Connection failed")
                    val reason = (_state.value as? VideoState.Failed)?.reason
                    Log.w(LINK_TAG, "whep_failed base=${source.baseUrl} stream=${source.streamId} reason=$reason")
                    delay(backoff.nextDelayMs())
                }
            }
        }
    }

    private suspend fun dial() {
        frames.reset()
        key = apiKey()
        var p = peerFactory.create(frames)
        peer = p
        var offer = p.createOffer()
        // A first peer can finish gathering before the network list is ready and offer no
        // candidate at all; that dial can never connect, so a fresh peer replaces it at once.
        if (!hasCandidate(offer)) {
            Log.w(LINK_TAG, "whep_offer_empty base=${source.baseUrl} stream=${source.streamId}")
            p.close()
            p = peerFactory.create(frames)
            peer = p
            offer = p.createOffer()
        }
        val answer = signaling.offer(source.baseUrl, source.streamId, key, offer)
        resource = answer.resourceUrl
        p.applyAnswer(answer.sdp)
    }

    private fun hasCandidate(sdp: String) = sdp.lineSequence().any { it.startsWith("a=candidate") }

    /**
     * Watches one dialled peer until it needs replacing. [Outcome.Flowed] means
     * frames flowed before the link dropped, which refills the freeze budget;
     * [Outcome.Redecode] means the decoder output changed and the next dial takes it.
     */
    private suspend fun monitor(): Outcome {
        val p = peer ?: return Outcome.Failed
        val dialedAt = clock.nowMs()
        var connectedAt: Long? = null
        var loggedState: PeerLinkState? = null
        var disconnectedAt: Long? = null
        var flowed = false
        var lastStatsAt = Long.MIN_VALUE
        var latency: Int? = null
        val meter = VideoStatsMeter()
        val handoff = HandoffLossMonitor()
        while (true) {
            val now = clock.nowMs()
            val link = p.connection.value
            if (link != loggedState) {
                loggedState = link
                Log.i(LINK_TAG, "whep_peer state=$link at_ms=${now - dialedAt}")
            }
            if (link == PeerLinkState.Connected && connectedAt == null) connectedAt = now
            when (link) {
                PeerLinkState.Failed, PeerLinkState.Closed -> return Outcome.Failed
                PeerLinkState.Disconnected -> {
                    val since = disconnectedAt ?: now.also { disconnectedAt = it }
                    if (now - since >= DISCONNECT_GRACE_MS) return if (flowed) Outcome.Flowed else Outcome.Lost
                }
                else -> disconnectedAt = null
            }
            when (val verdict = frames.decoded.check()) {
                is FrameWatchdog.Verdict.Frozen -> {
                    _state.value = VideoState.Frozen(verdict.sinceMs)
                    return Outcome.Frozen
                }
                FrameWatchdog.Verdict.Flowing -> {
                    if (!flowed) {
                        flowed = true
                        backoff.reset()
                        Log.i(LINK_TAG, "whep_first_frame at_ms=${now - dialedAt}")
                    }
                    if (lastStatsAt == Long.MIN_VALUE || now - lastStatsAt >= STATS_INTERVAL_MS) {
                        lastStatsAt = now
                        val rates = meter.update(now, p.inboundStats(), frames.decoded.frameCount, frames.shown.frameCount)
                        if (rates != null) {
                            _rates.value = rates
                            latency = rates.latencyMs ?: latency
                            Log.i(TAG, rates.logLine())
                        }
                        if (handoff.onRates(rates) && onHandoffLoss()) return Outcome.Redecode
                    }
                    _state.value = frames.flowingState(latency)
                }
                null -> when {
                    connectedAt == null && now - dialedAt >= CONNECT_TIMEOUT_MS -> {
                        _state.value = VideoState.Failed("ICE did not connect")
                        return Outcome.Failed
                    }
                    now - dialedAt >= FIRST_FRAME_TIMEOUT_MS -> {
                        _state.value = VideoState.Failed("No video frames")
                        return Outcome.Failed
                    }
                    connectedAt?.let { now - it >= FIRST_FRAME_AFTER_CONNECT_MS } == true -> return Outcome.Frozen
                }
            }
            delay(TICK_MS)
        }
    }

    /**
     * DELETE first, then close: closing the peer ends the server's session,
     * after which the DELETE finds nothing (404) and the release is unconfirmed.
     */
    private suspend fun teardown() {
        resource?.let { signaling.delete(it, key) }
        resource = null
        peer?.close()
        peer = null
    }

    private enum class Outcome { Flowed, Lost, Failed, Frozen, Redecode }

    companion object {
        const val DISCONNECT_GRACE_MS = 3_000L

        /** ICE must reach `connected` this long after the dial, else the dial failed. */
        const val CONNECT_TIMEOUT_MS = 3_000L

        /** A connected peer with no decoded frame this long after connecting is re-dialled once. */
        const val FIRST_FRAME_AFTER_CONNECT_MS = 3_000L

        /** Overall ceiling from the dial to the first decoded frame. */
        const val FIRST_FRAME_TIMEOUT_MS = 6_000L
        private const val STATS_INTERVAL_MS = 1_000L
        private const val TAG = "VideoStats"
        private const val LINK_TAG = "AdosLink"
        private const val TICK_MS = 250L
    }
}
