package com.altnautica.gcs.core.video.whep

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The process-wide WebRTC objects: one EGL context shared by every renderer
 * and one peer-connection factory with the hardware-first decoder factory.
 * Created on first use so screens without video never load the native library.
 */
@Singleton
class WebRtcEnvironment @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val eglBase: EglBase by lazy { EglBase.create() }

    val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(),
        )
        PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }
}
