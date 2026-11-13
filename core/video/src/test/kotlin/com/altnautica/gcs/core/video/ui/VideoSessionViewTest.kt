package com.altnautica.gcs.core.video.ui

import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.LocalView
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.VideoOutput
import com.altnautica.gcs.core.video.VideoRecording
import com.altnautica.gcs.core.video.VideoSessionHandle
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VideoSessionViewTest {

    @get:Rule
    val compose = createComposeRule()

    /** Records every surface handed to it; null means "detached". */
    private class FakeHandle(stream: String) : VideoSessionHandle {
        val surfaces = mutableListOf<Surface?>()
        override val source = VideoSource.AgentWhep(DeviceId("d1"), "http://192.168.1.50:8080/", stream)
        override val state = MutableStateFlow<VideoState>(VideoState.Live(null))
        override val output: VideoOutput = object : VideoOutput.SurfaceTarget {
            override fun setSurface(surface: Surface?) { surfaces += surface }
        }
        override val recording: VideoRecording? = null
        override fun start() = Unit
        override suspend fun stop() = Unit
    }

    @Test
    fun `switching the session at the same call site releases the old view and builds a new one`() {
        val a = FakeHandle("main")
        val b = FakeHandle("thermal")
        var current by mutableStateOf<VideoSessionHandle>(a)
        compose.setContent { VideoSessionView(current, VideoSurfaceRole.Main, Modifier) }
        compose.waitForIdle()
        assertEquals(emptyList<Surface?>(), a.surfaces)

        current = b
        compose.waitForIdle()

        // The old session's view was released and detached from it ...
        assertEquals(listOf<Surface?>(null), a.surfaces)
        // ... and the new session was not detached by a view that never belonged to it.
        assertEquals(emptyList<Surface?>(), b.surfaces)

        current = a
        compose.waitForIdle()
        assertEquals(listOf<Surface?>(null), b.surfaces)
    }

    private fun viewsIn(root: View): List<View> =
        listOf(root) + ((root as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { viewsIn(g.getChildAt(it)) } } ?: emptyList())

    private fun mainHandle(scale: Float) = object : VideoSessionHandle {
        override val source = VideoSource.AgentWhep(DeviceId("d1"), "demo://local/", "main")
        override val state = MutableStateFlow<VideoState>(VideoState.Live(null))
        override val output: VideoOutput = object : VideoOutput.SurfaceTarget {
            override fun setSurface(surface: Surface?) = Unit
            override val bufferScale: Float = scale
        }
        override val recording: VideoRecording? = null
        override fun start() = Unit
        override suspend fun stop() = Unit
    }

    private fun mainViewFor(scale: Float): List<View> {
        val handle = mainHandle(scale)
        lateinit var root: View
        compose.setContent {
            root = LocalView.current.rootView
            VideoSessionView(handle, VideoSurfaceRole.Main, Modifier)
        }
        compose.waitForIdle()
        return viewsIn(root)
    }

    /** Every view the main role reports while composed with [scale], then after it leaves. */
    private fun reportedViews(scale: Float): List<View?> {
        val reported = mutableListOf<View?>()
        val handle = mainHandle(scale)
        var shown by mutableStateOf(true)
        compose.setContent {
            if (shown) VideoSessionView(handle, VideoSurfaceRole.Main, Modifier, onVideoView = { reported += it })
        }
        compose.waitForIdle()
        shown = false
        compose.waitForIdle()
        return reported
    }

    @Test
    fun `a main video on a TextureView reports its view, and null once it is gone`() {
        val reported = reportedViews(0.5f)
        assertTrue(reported.first() is TextureView)
        assertEquals(null, reported.last())
    }

    @Test
    fun `a main video on a SurfaceView reports its view, and null once it is gone`() {
        val reported = reportedViews(1f)
        assertTrue(reported.first() is SurfaceView)
        assertEquals(null, reported.last())
    }

    @Test
    fun `a software canvas producer never draws into a SurfaceView, whose teardown would wait on its lock`() {
        val views = mainViewFor(0.5f)
        assertTrue(views.any { it is TextureView })
        assertTrue(views.none { it is SurfaceView })
    }

    @Test
    fun `a decoder keeps the SurfaceView in the main role`() {
        assertTrue(mainViewFor(1f).any { it is SurfaceView })
    }

    @Test
    fun `a picture-in-picture video draws into a TextureView, which its rounded clip can shape`() {
        val handle = mainHandle(1f)
        lateinit var root: View
        compose.setContent {
            root = LocalView.current.rootView
            VideoSessionView(handle, VideoSurfaceRole.Pip, Modifier)
        }
        compose.waitForIdle()
        val views = viewsIn(root)
        assertTrue(views.any { it is TextureView })
        assertTrue(views.none { it is SurfaceView })
    }

    @Test
    fun `a session switch ends reporting the new session's view`() {
        val reported = mutableListOf<View?>()
        val a = mainHandle(0.5f)
        val b = mainHandle(0.5f)
        var current by mutableStateOf<VideoSessionHandle>(a)
        compose.setContent { VideoSessionView(current, VideoSurfaceRole.Main, Modifier, onVideoView = { reported += it }) }
        compose.waitForIdle()
        val first = reported.last()
        current = b
        compose.waitForIdle()
        assertTrue(reported.last() is TextureView)
        assertTrue(reported.last() !== first)
    }

    @Test
    fun `releasing a view that is no longer shown keeps the shown one reported`() {
        val reported = mutableListOf<View?>()
        val reporter = ViewReporter().apply { sink = { reported += it } }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val old = View(context)
        val new = View(context)
        reporter.show(old)
        reporter.show(new)
        reporter.release(old)
        assertEquals(listOf<View?>(old, new), reported)
        reporter.release(new)
        assertEquals(null, reported.last())
    }
}
