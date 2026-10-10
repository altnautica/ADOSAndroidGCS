package com.altnautica.gcs.core.video.whep

import com.altnautica.gcs.core.video.demo.DemoVideoFactory
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.video.VideoClock
import com.altnautica.gcs.core.video.VideoScope
import com.altnautica.gcs.core.video.VideoSessionHandle
import com.altnautica.gcs.core.video.VideoSourceFactory
import com.altnautica.gcs.core.video.record.AgentRecordingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays [VideoSource.AgentWhep]. The source's node is the agent that serves
 * the `/whep` endpoint (the ground station itself for a drone it carries), so
 * its stored API key authenticates the offer and the DELETE.
 */
@Singleton
class WhepVideoFactory @Inject constructor(
    private val environment: WebRtcEnvironment,
    private val vault: CredentialVault,
    private val recordings: AgentRecordingProvider,
    @VideoScope private val scope: CoroutineScope,
    http: OkHttpClient,
) : VideoSourceFactory {

    private val signaling = WhepSignaling(
        http.newBuilder()
            .connectTimeout(OFFER_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(OFFER_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(OFFER_TIMEOUT_S, TimeUnit.SECONDS)
            .build(),
    )

    override fun supports(src: VideoSource): Boolean =
        src is VideoSource.AgentWhep && src.node != DemoVideoFactory.DEMO_NODE

    override fun create(src: VideoSource): VideoSessionHandle {
        val whep = src as VideoSource.AgentWhep
        return WhepSession(
            source = whep,
            scope = scope,
            signaling = signaling,
            peerFactory = { sink -> NativeWhepPeer(environment, sink) },
            apiKey = { withContext(Dispatchers.IO) { vault.apiKey(whep.node) } },
            eglContext = { environment.eglBase.eglBaseContext },
            clock = VideoClock.Elapsed,
            recording = recordings.forSource(whep),
        )
    }

    private companion object {
        const val OFFER_TIMEOUT_S = 6L
    }
}
