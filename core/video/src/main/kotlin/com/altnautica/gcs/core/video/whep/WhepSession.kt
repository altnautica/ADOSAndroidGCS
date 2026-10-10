package com.altnautica.gcs.core.video.whep

import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.FrameFanout
import com.altnautica.gcs.core.video.FrameWatchdog
import com.altnautica.gcs.core.video.RedialBackoff
import com.altnautica.gcs.core.video.VideoClock
import com.altnautica.gcs.core.video.VideoOutput
import com.altnautica.gcs.core.video.VideoRecording
import com.altnautica.gcs.core.video.VideoSessionHandle
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
 * - `failed` or a refused offer re-dials with 1→10 s backoff;
 * - no frame for 2.5 s marks the picture [VideoState.Frozen] and re-dials once;
 *   a second freeze before frames flow again backs off like a failure;
 * - [VideoState.Live] only while frames reach a renderer on screen.
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
) : VideoSessionHandle {

    private val _state = MutableStateFlow<VideoState>(VideoState.Idle)
    override val state: StateFlow<VideoState> = _state

    private val frames = FrameFanout(clock)
    private val backoff = RedialBackoff()

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
    }

    private suspend fun run() {
        var freezeRedialUsed = false
        while (scope.isActive) {
            _state.value = VideoState.Connecting
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
            if (outcome == Outcome.Flowed) freezeRedialUsed = false
            teardown()
            when (outcome) {
                Outcome.Frozen -> if (!freezeRedialUsed) {
                    freezeRedialUsed = true
                    continue
                } else {
                    delay(backoff.nextDelayMs())
                }
                Outcome.Lost, Outcome.Flowed -> Unit
                Outcome.Failed -> {
                    if (_state.value !is VideoState.Failed) _state.value = VideoState.Failed("Connection failed")
                    delay(backoff.nextDelayMs())
                }
            }
        }
    }

    private suspend fun dial() {
        frames.reset()
        key = apiKey()
        val p = peerFactory.create(frames)
        peer = p
        val offer = p.createOffer()
        val answer = signaling.offer(source.baseUrl, source.streamId, key, offer)
        resource = answer.resourceUrl
        p.applyAnswer(answer.sdp)
    }

    /**
     * Watches one dialled peer until it needs replacing. [Outcome.Flowed] means
     * frames flowed before the link dropped, which refills the freeze budget.
     */
    private suspend fun monitor(): Outcome {
        val p = peer ?: return Outcome.Failed
        val dialedAt = clock.nowMs()
        var disconnectedAt: Long? = null
        var flowed = false
        var lastStatsAt = Long.MIN_VALUE
        var latency: Int? = null
        while (true) {
            val now = clock.nowMs()
            when (p.connection.value) {
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
                    }
                    if (lastStatsAt == Long.MIN_VALUE || now - lastStatsAt >= STATS_INTERVAL_MS) {
                        lastStatsAt = now
                        latency = p.latencyMs() ?: latency
                    }
                    _state.value = frames.flowingState(latency)
                }
                null -> if (now - dialedAt >= FIRST_FRAME_TIMEOUT_MS) {
                    _state.value = VideoState.Failed("No video frames")
                    return Outcome.Failed
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

    private enum class Outcome { Flowed, Lost, Failed, Frozen }

    companion object {
        const val DISCONNECT_GRACE_MS = 3_000L
        const val FIRST_FRAME_TIMEOUT_MS = 10_000L
        private const val STATS_INTERVAL_MS = 1_000L
        private const val TICK_MS = 250L
    }
}
