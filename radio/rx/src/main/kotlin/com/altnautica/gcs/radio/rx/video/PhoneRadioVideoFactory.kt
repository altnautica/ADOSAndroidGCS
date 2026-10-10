package com.altnautica.gcs.radio.rx.video

import android.content.Context
import android.view.Surface
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.FrameWatchdog
import com.altnautica.gcs.core.video.VideoClock
import com.altnautica.gcs.core.video.VideoOutput
import com.altnautica.gcs.core.video.VideoRecording
import com.altnautica.gcs.core.video.VideoScope
import com.altnautica.gcs.core.video.VideoSessionHandle
import com.altnautica.gcs.core.video.VideoSourceFactory
import com.altnautica.gcs.core.video.codec.MediaCodecH264Decoder
import com.altnautica.gcs.core.video.record.H264Dvr
import com.altnautica.gcs.core.video.rtp.H264RtpDepacketizer
import com.altnautica.gcs.radio.rx.session.PhoneRadioSession
import com.altnautica.gcs.radio.rx.session.PhoneRadioState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Video for [VideoSource.PhoneRadio]: the drone's RTP plane decoded on the phone. */
@Singleton
class PhoneRadioVideoFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: PhoneRadioSession,
    @VideoScope private val scope: CoroutineScope,
) : VideoSourceFactory {

    override fun supports(src: VideoSource): Boolean = src is VideoSource.PhoneRadio

    override fun create(src: VideoSource): VideoSessionHandle {
        require(src is VideoSource.PhoneRadio) { "unsupported source $src" }
        return PhoneRadioVideoSession(src, src.node, session, scope, File(context.filesDir, DVR_DIR), VideoClock.Elapsed)
    }

    companion object {
        /** Local recordings land here; Logs lists them. */
        const val DVR_DIR = "dvr"
    }
}

internal class PhoneRadioVideoSession(
    override val source: VideoSource,
    private val node: DeviceId,
    private val radio: PhoneRadioSession,
    private val scope: CoroutineScope,
    dvrDir: File,
    private val clock: VideoClock,
) : VideoSessionHandle {

    private val _state = MutableStateFlow<VideoState>(VideoState.Idle)
    override val state: StateFlow<VideoState> = _state.asStateFlow()

    private val watchdog = FrameWatchdog(clock)
    private val decoder = MediaCodecH264Decoder(onFrameRendered = watchdog::onFrame)
    private val dvr = H264Dvr(dvrDir)
    override val recording: VideoRecording = dvr

    override val output: VideoOutput = object : VideoOutput.SurfaceTarget {
        override fun setSurface(surface: Surface?) {
            decoder.setSurface(surface)
        }
    }

    private var feed: Job? = null
    private var supervisor: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun start() {
        if (feed != null) return
        _state.value = VideoState.Connecting
        val depacketizer = H264RtpDepacketizer()
        feed = scope.launch(Dispatchers.Default.limitedParallelism(1)) {
            radio.video(node).collect { packet ->
                for (unit in depacketizer.push(packet)) {
                    dvr.offer(unit)
                    decoder.submit(unit, unit.rtpTimestamp * 1_000_000L / RTP_CLOCK)
                }
            }
        }
        supervisor = scope.launch {
            while (isActive) {
                _state.value = stateNow()
                delay(SUPERVISE_MS)
            }
        }
    }

    private fun stateNow(): VideoState = when (val r = radio.state.value) {
        PhoneRadioState.Stopped -> VideoState.Failed("Phone radio is not receiving")
        is PhoneRadioState.Failed -> VideoState.Failed("Phone radio stopped: ${r.reason}")
        PhoneRadioState.Starting -> VideoState.Connecting
        is PhoneRadioState.Receiving -> when (val v = watchdog.check()) {
            null -> VideoState.Connecting
            FrameWatchdog.Verdict.Flowing -> VideoState.Live(null)
            is FrameWatchdog.Verdict.Frozen -> VideoState.Frozen(v.sinceMs)
        }
    }

    override suspend fun stop() {
        supervisor?.cancelAndJoin()
        feed?.cancelAndJoin()
        supervisor = null
        feed = null
        dvr.stop()
        decoder.release()
        watchdog.reset()
        _state.value = VideoState.Idle
    }

    private companion object {
        const val RTP_CLOCK = 90_000L
        const val SUPERVISE_MS = 250L
    }
}
