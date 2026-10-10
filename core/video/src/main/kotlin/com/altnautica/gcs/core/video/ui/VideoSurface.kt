package com.altnautica.gcs.core.video.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.VideoOutput
import com.altnautica.gcs.core.video.VideoSessionHandle
import com.altnautica.gcs.core.video.VideoSessionManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.flowOf
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** Main feed draws on a SurfaceView; a picture-in-picture second stream on a TextureView. */
enum class VideoSurfaceRole { Main, Pip }

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface VideoEntryPoint {
    fun videoSessionManager(): VideoSessionManager
}

@Composable
private fun rememberVideoSessionManager(): VideoSessionManager {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        EntryPointAccessors.fromApplication(context, VideoEntryPoint::class.java).videoSessionManager()
    }
}

/**
 * Shows [source] and keeps its session alive while composed. A null source,
 * or one no factory can play, draws nothing; read [rememberVideoState] to
 * show the matching empty state over it.
 */
@Composable
fun VideoSurface(
    source: VideoSource?,
    modifier: Modifier = Modifier,
    role: VideoSurfaceRole = VideoSurfaceRole.Main,
) {
    val manager = rememberVideoSessionManager()
    var handle by remember { mutableStateOf<VideoSessionHandle?>(null) }
    DisposableEffect(source, manager) {
        val lease = source?.let(manager::acquire)
        handle = lease?.handle
        onDispose {
            handle = null
            lease?.release()
        }
    }
    val h = handle ?: return
    VideoSessionView(h, role, modifier)
}

/**
 * The view for one session. Keyed on [handle]: when the source changes at the
 * same call site (stream tabs, main/PiP swap) the old renderer is released from
 * the old session and a new one is built for the new session, so the picture
 * on screen is always the session whose state the band reports.
 */
@Composable
internal fun VideoSessionView(handle: VideoSessionHandle, role: VideoSurfaceRole, modifier: Modifier) {
    key(handle) {
        when (val out = handle.output) {
            is VideoOutput.Sinks -> when (role) {
                VideoSurfaceRole.Main -> SinkSurfaceView(out, modifier)
                VideoSurfaceRole.Pip -> SinkTextureView(out, modifier)
            }
            is VideoOutput.SurfaceTarget -> when (role) {
                VideoSurfaceRole.Main -> TargetSurfaceView(out, modifier)
                VideoSurfaceRole.Pip -> TargetTextureView(out, modifier)
            }
        }
    }
}

/** State of [source]'s session; [VideoState.NoSource] when [source] is null or unplayable. */
@Composable
fun rememberVideoState(source: VideoSource?): State<VideoState> {
    val manager = rememberVideoSessionManager()
    val flow = remember(source, manager) {
        when {
            source == null || !manager.canPlay(source) -> flowOf(VideoState.NoSource)
            else -> manager.state(source)
        }
    }
    return flow.collectAsStateWithLifecycle(VideoState.Idle)
}

/**
 * The renderer joins the session only while its surface exists, so the
 * session reports LIVE only for frames that have somewhere to draw. Our
 * holder callback runs after the renderer's own (registered in its
 * constructor), so the EGL surface is ready before the first frame arrives.
 */
@Composable
private fun SinkSurfaceView(out: VideoOutput.Sinks, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx: Context ->
            SurfaceViewRenderer(ctx).apply {
                init(out.eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                val renderer = this
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = out.addSink(renderer)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = out.removeSink(renderer)
                })
            }
        },
        onRelease = { view ->
            out.removeSink(view)
            view.release()
        },
    )
}

@Composable
private fun SinkTextureView(out: VideoOutput.Sinks, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx: Context ->
            val renderer = EglRenderer("pip")
            renderer.init(out.eglContext, org.webrtc.EglBase.CONFIG_PLAIN, GlRectDrawer())
            TextureView(ctx).apply {
                tag = renderer
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        renderer.setLayoutAspectRatio(w.toFloat() / h.coerceAtLeast(1))
                        renderer.createEglSurface(st)
                        out.addSink(renderer)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                        renderer.setLayoutAspectRatio(w.toFloat() / h.coerceAtLeast(1))
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        out.removeSink(renderer)
                        renderer.releaseEglSurface { }
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        onRelease = { view ->
            val renderer = view.tag as EglRenderer
            out.removeSink(renderer)
            renderer.release()
        },
    )
}

@Composable
private fun TargetSurfaceView(out: VideoOutput.SurfaceTarget, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx: Context ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = out.setSurface(holder.surface)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = out.setSurface(null)
                })
                if (out.bufferScale < 1f) {
                    addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                        val (w, h) = scaledBufferSize(r - l, b - t, out.bufferScale)
                        if (w > 0 && h > 0) holder.setFixedSize(w, h)
                    }
                }
            }
        },
        onRelease = { out.setSurface(null) },
    )
}

@Composable
private fun TargetTextureView(out: VideoOutput.SurfaceTarget, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx: Context ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null

                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        applyBufferScale(st, w, h)
                        surface = Surface(st).also(out::setSurface)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyBufferScale(st, w, h)

                    private fun applyBufferScale(st: SurfaceTexture, w: Int, h: Int) {
                        if (out.bufferScale >= 1f) return
                        val (bw, bh) = scaledBufferSize(w, h, out.bufferScale)
                        if (bw > 0 && bh > 0) st.setDefaultBufferSize(bw, bh)
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        out.setSurface(null)
                        surface?.release()
                        surface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        onRelease = { out.setSurface(null) },
    )
}

/** The view size scaled by [scale], rounded to even pixels (some gralloc paths reject odd sizes). */
internal fun scaledBufferSize(width: Int, height: Int, scale: Float): Pair<Int, Int> =
    ((width * scale).toInt() and 1.inv()) to ((height * scale).toInt() and 1.inv())
