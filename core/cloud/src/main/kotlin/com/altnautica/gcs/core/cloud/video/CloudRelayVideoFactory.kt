package com.altnautica.gcs.core.cloud.video

import android.view.Surface
import com.altnautica.gcs.core.cloud.CloudScope
import com.altnautica.gcs.core.cloud.config.CloudConfigRepository
import com.altnautica.gcs.core.cloud.convex.CloudJson
import com.altnautica.gcs.core.cloud.convex.ConvexGateway
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.FrameWatchdog
import com.altnautica.gcs.core.video.VideoClock
import com.altnautica.gcs.core.video.VideoOutput
import com.altnautica.gcs.core.video.VideoRecording
import com.altnautica.gcs.core.video.VideoSessionHandle
import com.altnautica.gcs.core.video.VideoSourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A viewer token for the video relay, or the deployment's answer that it has none. */
sealed interface RelayToken {
    data class Ok(val token: String) : RelayToken
    data object NotConfigured : RelayToken
}

fun interface RelayTokenSource {
    suspend fun mint(deviceId: String): RelayToken
}

/** What one relay socket reports. */
sealed interface RelayEvent {
    data object Opened : RelayEvent
    class Bytes(val data: ByteArray) : RelayEvent
    data class Closed(val reason: String?) : RelayEvent
}

/** One open relay socket. */
fun interface RelayConnection {
    fun close()
}

fun interface RelayDialer {
    fun dial(url: String, events: (RelayEvent) -> Unit): RelayConnection
}

class ConvexRelayTokenSource(private val gateway: ConvexGateway) : RelayTokenSource {
    @Serializable
    private data class Minted(val status: String, val token: String? = null)

    override suspend fun mint(deviceId: String): RelayToken {
        val r = CloudJson.decodeFromJsonElement(
            Minted.serializer(),
            gateway.action("cmdVideoRelayTokens:mint", mapOf("deviceId" to deviceId)),
        )
        return when {
            r.status == "not-configured" -> RelayToken.NotConfigured
            r.status == "ok" && !r.token.isNullOrBlank() -> RelayToken.Ok(r.token)
            else -> throw IllegalStateException("Video relay token unavailable")
        }
    }
}

class OkHttpRelayDialer(private val client: OkHttpClient) : RelayDialer {
    override fun dial(url: String, events: (RelayEvent) -> Unit): RelayConnection {
        val ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = events(RelayEvent.Opened)
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = events(RelayEvent.Bytes(bytes.toByteArray()))
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    events(RelayEvent.Closed(reason.ifBlank { "closed ($code)" }))
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    events(RelayEvent.Closed(reason.ifBlank { "closed ($code)" }))
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    events(RelayEvent.Closed(t.message ?: response?.message ?: "failed"))
            },
        )
        return RelayConnection { ws.cancel() }
    }
}

/** Plays [VideoSource.CloudRelay]: the fragmented-MP4 stream the cloud relay forwards. */
@Singleton
class CloudRelayVideoFactory @Inject constructor(
    gateway: ConvexGateway,
    http: OkHttpClient,
    private val config: CloudConfigRepository,
    @param:CloudScope private val scope: CoroutineScope,
) : VideoSourceFactory {

    private val tokens = ConvexRelayTokenSource(gateway)
    private val dialer = OkHttpRelayDialer(
        http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build(),
    )

    override fun supports(src: VideoSource): Boolean = src is VideoSource.CloudRelay

    override fun create(src: VideoSource): VideoSessionHandle =
        CloudRelaySession(
            source = src as VideoSource.CloudRelay,
            scope = scope,
            tokens = tokens,
            dialer = dialer,
            relayUrl = { config.current().videoRelayUrl },
            decoderFactory = { onFrame -> MediaCodecRelayDecoder(onFrame) },
            clock = VideoClock.Elapsed,
        )
}

/**
 * One relay viewer. Before every dial a fresh token is minted (they live five
 * minutes); a socket that closes before it opened means the token was refused
 * or the relay is unreachable, and is retried every [RETRY_MS] with a new
 * token. A deployment without a relay (`not-configured`) is terminal.
 */
class CloudRelaySession(
    override val source: VideoSource.CloudRelay,
    private val scope: CoroutineScope,
    private val tokens: RelayTokenSource,
    private val dialer: RelayDialer,
    private val relayUrl: suspend () -> String,
    decoderFactory: (onFrame: () -> Unit) -> RelaySampleDecoder,
    private val clock: VideoClock,
) : VideoSessionHandle {

    private val _state = MutableStateFlow<VideoState>(VideoState.Idle)
    override val state: StateFlow<VideoState> = _state
    override val recording: VideoRecording? = null

    private val watchdog = FrameWatchdog(clock)
    private val decoder = decoderFactory { watchdog.onFrame() }

    override val output: VideoOutput = object : VideoOutput.SurfaceTarget {
        override fun setSurface(surface: Surface?) = decoder.setSurface(surface)
    }

    private var job: Job? = null
    private var connection: RelayConnection? = null

    override fun start() {
        if (job != null) return
        job = scope.launch { run() }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        withContext(NonCancellable) {
            connection?.close()
            connection = null
            decoder.release()
        }
        _state.value = VideoState.Idle
    }

    private suspend fun run() {
        while (scope.isActive) {
            _state.value = VideoState.Connecting
            val token = try {
                tokens.mint(source.node.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = VideoState.Failed(e.message ?: "Video relay token unavailable")
                delay(RETRY_MS)
                continue
            }
            if (token is RelayToken.NotConfigured) {
                _state.value = VideoState.Failed("The cloud video relay is not configured")
                return
            }
            val url = streamUrl(relayUrl(), source.node.value, (token as RelayToken.Ok).token)
            watch(url)
            delay(RETRY_MS)
        }
    }

    /** Dials once and plays until the socket closes. */
    private suspend fun watch(url: String) {
        val events = Channel<RelayEvent>(Channel.UNLIMITED)
        val conn = dialer.dial(url) { events.trySend(it) }
        connection = conn
        val demuxer = Fmp4Demuxer()
        var opened = false
        watchdog.reset()
        try {
            while (true) {
                val ev = withTimeoutOrNull(TICK_MS) { events.receive() }
                if (ev == null) {
                    if (opened) updateLiveness()
                    continue
                }
                when (ev) {
                    RelayEvent.Opened -> opened = true
                    is RelayEvent.Bytes -> {
                        opened = true
                        val wasConfigured = demuxer.track != null
                        val samples = try {
                            demuxer.push(ev.data)
                        } catch (e: Fmp4Exception) {
                            _state.value = VideoState.Failed(e.message ?: "Unreadable video stream")
                            return
                        }
                        if (!wasConfigured) demuxer.track?.let { decoder.configure(it.codec) }
                        for (s in samples) decoder.submit(s)
                    }
                    is RelayEvent.Closed -> {
                        _state.value = VideoState.Failed(
                            if (opened) "The video relay closed the stream" else TOKEN_REFUSED,
                        )
                        return
                    }
                }
            }
        } finally {
            conn.close()
            connection = null
            events.close()
        }
    }

    private fun updateLiveness() {
        when (val v = watchdog.check()) {
            FrameWatchdog.Verdict.Flowing -> _state.value = VideoState.Live(null)
            is FrameWatchdog.Verdict.Frozen -> _state.value = VideoState.Frozen(v.sinceMs)
            null -> Unit
        }
    }

    companion object {
        const val RETRY_MS = 3_000L
        private const val TICK_MS = 250L
        const val TOKEN_REFUSED =
            "The video relay closed the connection before it opened: the viewer token was refused or the relay is unreachable"

        fun streamUrl(relayUrl: String, deviceId: String, token: String): String =
            "${relayUrl.trimEnd('/')}/ws/stream/$deviceId?token=${URLEncoder.encode(token, "UTF-8")}"
    }
}
