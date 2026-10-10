package com.altnautica.gcs.core.cloud.video

import com.altnautica.gcs.core.cloud.CloudScope
import com.altnautica.gcs.core.cloud.config.CloudConfigRepository
import com.altnautica.gcs.core.cloud.grant.GrantSource
import com.altnautica.gcs.core.cloud.mqtt.MqttPortFactory
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.FrameWatchdog
import com.altnautica.gcs.core.video.RedialBackoff
import com.altnautica.gcs.core.video.VideoClock
import com.altnautica.gcs.core.video.VideoOutput
import com.altnautica.gcs.core.video.VideoRecording
import com.altnautica.gcs.core.video.VideoSessionHandle
import com.altnautica.gcs.core.video.VideoSourceFactory
import com.altnautica.gcs.core.video.whep.PeerLinkState
import com.altnautica.gcs.core.video.whep.WebRtcEnvironment
import com.altnautica.gcs.core.video.whep.WhepPeer
import com.altnautica.gcs.core.video.whep.WhepPeerFactory
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
import org.webrtc.EglBase
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton

/** Plays [VideoSource.CloudP2p]: WebRTC negotiated over the cloud broker. */
@Singleton
class CloudP2pVideoFactory @Inject constructor(
    private val environment: WebRtcEnvironment,
    private val ports: MqttPortFactory,
    private val grants: GrantSource,
    private val config: CloudConfigRepository,
    @param:CloudScope private val scope: CoroutineScope,
) : VideoSourceFactory {

    override fun supports(src: VideoSource): Boolean = src is VideoSource.CloudP2p

    override fun create(src: VideoSource): VideoSessionHandle = CloudP2pSession(
        source = src as VideoSource.CloudP2p,
        scope = scope,
        signaling = P2pSignaling(ports),
        brokerUrl = { config.current().mqttBrokerUrl },
        credentials = { grants.state.value.grant?.let { it.principal to it.secret } },
        peerFactory = { sink -> CloudWebRtcPeer(environment, sink) },
        eglContext = { environment.eglBase.eglBaseContext },
        clock = VideoClock.Elapsed,
    )
}

/**
 * One cloud peer-to-peer leg with the same re-dial policy as a LAN WHEP leg:
 * `disconnected` gets a 3 s grace, `failed` or a refused exchange backs off
 * 1→10 s, a 2.5 s freeze re-dials once. Every signalling session is closed
 * (`{sessionId, close:true}`) before the next dial and on [stop].
 */
class CloudP2pSession(
    override val source: VideoSource.CloudP2p,
    private val scope: CoroutineScope,
    private val signaling: P2pSignaling,
    private val brokerUrl: suspend () -> String,
    private val credentials: () -> Pair<String, String>?,
    private val peerFactory: WhepPeerFactory,
    private val eglContext: () -> EglBase.Context,
    private val clock: VideoClock,
) : VideoSessionHandle {

    private val _state = MutableStateFlow<VideoState>(VideoState.Idle)
    override val state: StateFlow<VideoState> = _state
    override val recording: VideoRecording? = null

    private val watchdog = FrameWatchdog(clock)
    private val backoff = RedialBackoff()
    private val sinks = CopyOnWriteArraySet<VideoSink>()
    private val fanout = VideoSink { frame: VideoFrame ->
        watchdog.onFrame()
        for (s in sinks) s.onFrame(frame)
    }

    override val output: VideoOutput = object : VideoOutput.Sinks {
        override val eglContext: EglBase.Context get() = this@CloudP2pSession.eglContext()
        override fun addSink(sink: VideoSink) { sinks += sink }
        override fun removeSink(sink: VideoSink) { sinks -= sink }
    }

    private var job: Job? = null
    private var peer: WhepPeer? = null
    private var session: SignalingSession? = null

    override fun start() {
        if (job != null) return
        job = scope.launch { run() }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        withContext(NonCancellable) { teardown() }
        sinks.clear()
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
                _state.value = VideoState.Failed(e.message ?: "Cloud video failed")
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
        watchdog.reset()
        val (user, pass) = credentials() ?: throw P2pSignalingException("No cloud control grant is held")
        val p = peerFactory.create(fanout)
        peer = p
        val offer = p.createOffer()
        val s = signaling.exchange(brokerUrl(), source.node.value, user, pass, offer)
        session = s
        p.applyAnswer(s.answerSdp)
    }

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
            when (val verdict = watchdog.check()) {
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
                    _state.value = VideoState.Live(latency)
                }
                null -> if (now - dialedAt >= P2pTimeouts.FIRST_FRAME_MS) {
                    _state.value = VideoState.Failed("No video received within ${P2pTimeouts.FIRST_FRAME_MS / 1000} s")
                    return Outcome.Failed
                }
            }
            delay(TICK_MS)
        }
    }

    private suspend fun teardown() {
        peer?.close()
        peer = null
        session?.release()
        session = null
    }

    private enum class Outcome { Flowed, Lost, Failed, Frozen }

    private companion object {
        const val DISCONNECT_GRACE_MS = 3_000L
        const val STATS_INTERVAL_MS = 1_000L
        const val TICK_MS = 250L
    }
}
