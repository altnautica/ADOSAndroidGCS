package com.altnautica.gcs.feature.cockpit

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.designsystem.component.overlay.OverlayTopBar
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.Attitude
import com.altnautica.gcs.core.model.BatteryState
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.LocalPosition
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.core.model.StatusText
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.model.Vfr
import com.altnautica.gcs.core.extensions.ui.ExtensionMarksViewModel
import com.altnautica.gcs.core.extensions.ui.ExtensionSlotViewModel
import com.altnautica.gcs.core.skills.tips.TipsRepository
import com.altnautica.gcs.core.video.FrameFanout
import com.altnautica.gcs.core.video.VideoClock
import com.altnautica.gcs.core.video.VideoOutput
import com.altnautica.gcs.core.video.VideoSessionHandle
import com.altnautica.gcs.core.video.VideoSessionManager
import com.altnautica.gcs.core.video.VideoSourceFactory
import com.altnautica.gcs.core.video.VideoSourceSelector
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerFrame
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerParse
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import com.altnautica.gcs.feature.cockpit.tips.TipsEntryPoint
import dagger.hilt.internal.GeneratedComponentManager
import dagger.hilt.internal.GeneratedComponent
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import kotlin.math.sin
import kotlin.time.Duration.Companion.minutes

/**
 * The whole flight screen during flight, measured: a 30 fps video stream through
 * [FrameFanout], telemetry on every topic at the video rate and data-layer frames at
 * 15 Hz. It counts every composable's recompositions (the runtime's composition trace)
 * and every window draw (the analogue of the frames `dumpsys gfxinfo` counts). Nothing
 * may redraw the window or recompose any composable faster than the HUD's 10 Hz.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalComposeTracingApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w914dp-h411dp-land-xxhdpi", application = RedrawTestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlightScreenRedrawTest {
    @get:Rule
    val compose = createComposeRule()

    private val counter = CompositionCounter()

    @After
    fun tearDown() {
        Composer.setTracer(CompositionCounter.Off)
    }

    @Test
    fun `a 30 fps stream in flight redraws and recomposes the flight screen at most at the HUD rate`() {
        val m = measureFlight(seconds = 4)
        println(m.report())
        assertTrue("the stream reached the renderer: ${m.shownFps} fps", m.shownFps >= 29.0)
        assertEquals("the flight screen root never recomposes", 0.0, m.perSecond["com.altnautica.gcs.feature.cockpit.CockpitScreen"] ?: 0.0, 0.0)
        assertEquals("the HUD never recomposes", 0.0, m.perSecond["com.altnautica.gcs.feature.cockpit.hud.HudCanvas"] ?: 0.0, 0.0)
        // A window can hold one beat more than its length allows, depending on the beat's phase.
        val slack = 1.0 / m.seconds
        val minimap = m.perSecond["com.altnautica.gcs.feature.cockpit.hud.LocalMinimap"] ?: 0.0
        assertTrue("the local minimap follows the pose at most 5 times a second: $minimap", minimap in 1.0..5.0 + slack)
        val limit = MAX_PER_SECOND + slack
        val busy = m.perSecond.filterValues { it > limit }
        assertTrue("composables recomposing faster than ${MAX_PER_SECOND.toInt()} Hz: $busy", busy.isEmpty())
        assertTrue("window draws ${m.drawsPerSecond}/s, more than ${MAX_PER_SECOND.toInt()}", m.drawsPerSecond <= limit)
        assertTrue("the HUD and layers still redraw: ${m.drawsPerSecond}/s", m.drawsPerSecond >= 5.0)
    }

    /** Counts over [elapsedMs] of flight: whole frames, so slightly more than the asked span. */
    private class Measurement(elapsedMs: Long, val counts: Map<String, Int>, val draws: Int, val shown: Long) {
        val seconds = elapsedMs / 1_000.0
        val perSecond: Map<String, Double> = counts.mapValues { it.value / seconds }
        val drawsPerSecond = draws / seconds
        val shownFps = shown / seconds

        fun report(): String = buildString {
            appendLine("window draws/s=$drawsPerSecond shown_fps=$shownFps")
            perSecond.entries.filter { it.value >= 2.0 }.sortedByDescending { it.value }.forEach { (k, v) -> appendLine("  ${"%.1f".format(v)}/s $k") }
        }
    }

    /** Settles the screen for 3 s of flight, then measures [seconds] more. */
    private fun measureFlight(seconds: Int): Measurement {
        var result: Measurement? = null
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher, timeout = 5.minutes) {
                val vehicle = FakeVehicle()
                val now = { testScheduler.currentTime }
                val video = SimulatedVideo(VideoClock { testScheduler.currentTime })
                val sessions = VideoSessionManager(setOf(video), backgroundScope)
                val vm = testCockpitVm(vehicle, videoSelector = VideoSourceSelector(setOf(video)), videoSessions = sessions)
                RedrawTestApp.sessions = sessions
                vehicle.heartbeatAt(now(), armed = true, mode = "LOITER")
                // The bench's card: the local minimap, which follows the drone's pose.
                vm.autonomy.setMinimapPin(CockpitSettingsRepository.MINIMAP_LOCAL)
                runCurrent()
                val hiltActivity = Robolectric.buildActivity(RedrawTestActivity::class.java).create().get()
                var draws = 0
                var window: android.view.View? = null
                compose.mainClock.autoAdvance = false
                compose.setContent {
                    window = LocalView.current
                    // The extension slots reach their view models through Hilt's factory, which needs an
                    // activity with a component; a store that already holds empty ones answers them (no
                    // extension contributes anything here).
                    CompositionLocalProvider(LocalContext provides hiltActivity, LocalViewModelStoreOwner provides extensionViewModels(vehicle.id.value)) {
                        AdosTheme {
                            CockpitScreen(vm, WidthClass.Expanded, connecting = false, topBar = OverlayTopBar(), onExit = {}, snackbar = {})
                        }
                    }
                }
                val frameCanvas = android.graphics.Canvas(android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888))
                val ar = LayerParse.ar(javaClass.classLoader!!.getResource("fixtures/ar-frame.json")!!.readText())
                var step = 0
                fun fly(ms: Long) {
                    val end = now() + ms
                    while (now() < end) {
                        val t = now()
                        val s = step++
                        val wave = sin(s / 10.0).toFloat()
                        val tel = vehicle.telemetry
                        tel.attitude.value = Attitude(0.05f * wave, 0.02f, 1f + 0.01f * s, t)
                        tel.position.value = PositionFix(47.0 + s * 1e-6, 8.0, 430f, 12f + wave, 3f, 1f, -0.2f * wave, t)
                        tel.vfr.value = Vfr(3f + wave, 3.1f + wave, 57f + s * 0.1f, 45, -0.2f * wave, t)
                        tel.localPosition.value = LocalPosition(s * 0.1f, wave, -12f, 3f, 1f, 0.2f, t)
                        tel.battery.value = BatteryState(0, 15.8f - s * 1e-4f, 12f + wave, 88, null, t)
                        tel.gps.value = GpsState(GpsFix.Fix3D, 14, 0.7f, t)
                        if (s % 30 == 0) vehicle.heartbeatAt(t, armed = true, mode = "LOITER")
                        // An unacknowledged warning keeps the status pill flashing throughout.
                        if (s == 0) tel.statusText.tryEmit(StatusText(4, "GPS glitch", t))
                        // Layer frames at about 15 Hz, as the agent streams them.
                        if (s % 2 == 0) vm.autonomy.layers.store.push(LayerFrame.Ar(ar.copy(tsMs = ar.tsMs + s)), t)
                        video.frame()
                        testScheduler.advanceTimeBy(FRAME_MS)
                        runCurrent()
                        compose.mainClock.advanceTimeBy(FRAME_MS)
                        // One display frame: Robolectric runs no render thread, so a frame in which
                        // anything invalidated the window is counted here and drawn by hand, which
                        // clears the dirty flags for the next frame.
                        val root = window!!
                        if (root.isDirty) {
                            draws++
                            root.draw(frameCanvas)
                        }
                        counter.endFrame()
                    }
                }
                compose.mainClock.advanceTimeByFrame()
                fly(3_000)
                assertTrue("the video plays: ${vm.videoState.value}, main=${vm.video.value.main}", vm.videoState.value is VideoState.Live)
                val shownBefore = video.fanout.shown.frameCount
                Composer.setTracer(counter)
                val drawsBefore = draws
                val startMs = now()
                fly(seconds * 1_000L)
                Composer.setTracer(CompositionCounter.Off)
                result = Measurement(now() - startMs, counter.counts.toMap(), draws - drawsBefore, video.fanout.shown.frameCount - shownBefore)
                vm.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
        return result!!
    }

    private companion object {
        const val FRAME_MS = 33L
        const val MAX_PER_SECOND = 10.0
    }
}

/**
 * A view-model store already holding relaxed (empty) extension view models under the keys the
 * flight screen's slots ask for. It is not a [androidx.lifecycle.HasDefaultViewModelProviderFactory],
 * so `hiltViewModel` looks them up instead of building a Hilt factory.
 */
internal fun extensionViewModels(node: String): ViewModelStoreOwner {
    val owner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    val provider = ViewModelProvider.create(
        owner,
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = mockkClass(modelClass, relaxed = true)
        },
    )
    for (slot in listOf("video.overlay", "cockpit.widget", "map.overlay", "cockpit.panel")) {
        val slotVm = provider["extension-slot:$node:$slot", ExtensionSlotViewModel::class]
        every { slotVm.prompts } returns MutableStateFlow(emptyList())
        every { slotVm.mounts(any(), any(), any()) } returns flowOf(emptyList())
    }
    val marksVm = provider[ExtensionMarksViewModel::class]
    every { marksVm.marks(any()) } returns flowOf(emptyList())
    return owner
}

/**
 * Counts, per composable function (by name), the display frames in which it ran (first
 * composition or a recomposition). Counting frames rather than calls keeps a function called
 * from many places, or several lambdas of one parent, at one per frame.
 */
@OptIn(InternalComposeTracingApi::class)
internal class CompositionCounter : CompositionTracer {
    val counts = HashMap<String, Int>()
    private val frame = HashSet<String>()

    override fun isTraceInProgress() = true

    override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
        frame += info.substringBefore(" (")
    }

    /** Closes one display frame. */
    fun endFrame() {
        for (name in frame) counts[name] = (counts[name] ?: 0) + 1
        frame.clear()
    }

    override fun traceEventEnd() = Unit

    companion object {
        val Off = object : CompositionTracer {
            override fun isTraceInProgress() = false
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) = Unit
            override fun traceEventEnd() = Unit
        }
    }
}

/**
 * A drone camera whose decoded frames go through a [FrameFanout] to a renderer, as a WebRTC
 * session's do; it claims every node. Robolectric never creates the SurfaceView's surface, so
 * the renderer is attached when the session starts, as the view would on a device.
 */
internal class SimulatedVideo(private val clock: VideoClock) : VideoSourceFactory, VideoSessionHandle {
    val fanout = FrameFanout(clock)
    private val renderer = VideoSink { }
    private val picture = mockk<VideoFrame>(relaxed = true)
    private var latency = 40
    private val _state = MutableStateFlow<VideoState>(VideoState.Idle)
    override lateinit var source: VideoSource
    override val state = _state
    override val recording = null
    override val output = object : VideoOutput.SurfaceTarget {
        override fun setSurface(surface: android.view.Surface?) = Unit
    }

    /** One decoded frame; the session's live state carries a latency that changes every frame. */
    fun frame() {
        fanout.onFrame(picture)
        _state.value = fanout.flowingState(latency++ % 90)
    }

    override fun supports(src: VideoSource) = true
    override fun candidates(node: NodeSummary, stream: String?): List<VideoSource> = listOf(VideoSource.PhoneRadio(node.id))
    override fun create(src: VideoSource): VideoSessionHandle = also { source = src }
    override fun start() {
        fanout.output { error("no EGL in this test") }.addSink(renderer)
    }
    override suspend fun stop() = Unit
}

/**
 * The flight screen reaches the video sessions and the tips store through Hilt entry points;
 * this application answers both without a Hilt graph.
 */
class RedrawTestApp : Application(), GeneratedComponentManager<Any> {
    private val tips by lazy { TipsRepository(MemoryPreferences()) }

    override fun generatedComponent(): Any = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(GeneratedComponent::class.java, TipsEntryPoint::class.java, Class.forName("com.altnautica.gcs.core.video.ui.VideoEntryPoint")),
    ) { _, method, _ ->
        when (method.name) {
            "tips" -> tips
            "videoSessionManager" -> sessions
            "hashCode" -> 0
            "toString" -> "test component"
            "equals" -> false
            else -> error("no ${method.name} in this test")
        }
    }

    companion object {
        var sessions: VideoSessionManager? = null
    }
}

/** An activity whose component answers Hilt's view-model factory with no Hilt view models of its own. */
class RedrawTestActivity : ComponentActivity(), GeneratedComponentManager<Any> {
    override fun generatedComponent(): Any = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(GeneratedComponent::class.java, Class.forName("dagger.hilt.android.internal.lifecycle.HiltViewModelFactory\$ActivityCreatorEntryPoint")),
    ) { _, method, _ ->
        when (method.name) {
            "getViewModelKeys" -> emptyMap<Class<*>, Boolean>()
            "getViewModelComponentBuilder" -> null
            "hashCode" -> 0
            "toString" -> "test activity component"
            "equals" -> false
            else -> error("no ${method.name} in this test")
        }
    }
}
