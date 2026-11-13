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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import com.altnautica.gcs.core.designsystem.component.overlay.ProvideOverlayHost
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.extensions.ui.ExtensionMarksViewModel
import com.altnautica.gcs.core.extensions.ui.ExtensionSlotViewModel
import com.altnautica.gcs.core.skills.builtin.BuiltinIds
import com.altnautica.gcs.core.skills.tips.TipsRepository
import com.altnautica.gcs.core.video.FrameFanout
import com.altnautica.gcs.core.video.VideoClock
import com.altnautica.gcs.core.video.VideoOutput
import com.altnautica.gcs.core.video.VideoSessionHandle
import com.altnautica.gcs.core.video.VideoSessionManager
import com.altnautica.gcs.core.video.VideoSourceFactory
import com.altnautica.gcs.core.video.VideoSourceSelector
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.TestScope
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
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.minutes

/**
 * The whole flight screen in a live flight, measured. [LiveFlight] feeds it as the agent
 * feeds the bench: telemetry on every topic at the agent's flight rates, the pilot at 10 Hz on
 * two paths, the navigation stream, the AR and obstacle layers, detections, the battery engine
 * and a 30 fps video stream through [FrameFanout]. Each step is one 60 Hz display frame, so a
 * write off the beat shows as a frame of its own. It counts every composable's recompositions
 * (the runtime's composition trace), every window draw (the analogue of the frames `dumpsys
 * gfxinfo` counts), the emissions of the view-model flows the screen collects, and the main
 * thread's CPU time and allocations. Nothing may redraw the window or recompose any composable
 * faster than the HUD's 10 Hz beat.
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
    fun `a live flight redraws and recomposes the flight screen at most at the HUD rate`() = inFlight {
        val m = measure(seconds = 4)
        println(m.report())
        assertTrue("the stream reached the renderer: ${m.shownFps} fps", m.shownFps >= 29.0)
        assertEquals("the flight screen root never recomposes", 0.0, m.perSecond["com.altnautica.gcs.feature.cockpit.CockpitScreen"] ?: 0.0, 0.0)
        assertEquals("the HUD never recomposes", 0.0, m.perSecond["com.altnautica.gcs.feature.cockpit.hud.HudCanvas"] ?: 0.0, 0.0)
        // A window can hold one beat more than its length allows, depending on the beat's phase.
        val slack = 1.0 / m.seconds
        val limit = MAX_PER_SECOND + slack
        val busy = m.perSecond.filterValues { it > limit }
        assertTrue("composables recomposing faster than ${MAX_PER_SECOND.toInt()} Hz: $busy", busy.isEmpty())
        val fast = m.emissionsPerSecond.filterKeys { it in TICK_FLOWS }.filterValues { it > TICK_PER_SECOND + slack }
        assertTrue("2 Hz flows emitting off the tick: $fast", fast.isEmpty())
        assertTrue("window draws ${m.drawsPerSecond}/s, more than ${MAX_PER_SECOND.toInt()}", m.drawsPerSecond <= limit)
        assertTrue("the HUD and layers still redraw: ${m.drawsPerSecond}/s", m.drawsPerSecond >= 5.0)
    }

    @Test
    fun `the emergency stop sheet shows in the first frame after the tap`() = inFlight {
        fly(1_000)
        // The STOP button's own click handler (the harness's display host exposes no semantics for the top bar).
        compose.runOnIdle { vm.activate(BuiltinIds.KILL) }
        val title = testSpec.setupStrings.format("cockpit.estop")
        var frames = 0
        while (compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() && frames < 10) {
            frame()
            frames++
        }
        assertEquals("display frames from the tap to the sheet", 1, frames)
    }

    @Test
    fun `a pad button press and release never recompose the flight screen root`() = inFlight {
        fly(1_000)
        val buttons = vm.padButtons as MutableStateFlow<Int>
        Composer.setTracer(counter)
        counter.counts.clear()
        repeat(PRESSES) {
            buttons.value = 1
            fly(100)
            buttons.value = 0
            fly(100)
        }
        Composer.setTracer(CompositionCounter.Off)
        assertEquals("flight screen root recompositions over $PRESSES presses", 0, counter.counts["com.altnautica.gcs.feature.cockpit.CockpitScreen"] ?: 0)
    }

    /** Counts over [elapsedMs] of flight: whole frames, so slightly more than the asked span. */
    private class Measurement(
        elapsedMs: Long,
        val counts: Map<String, Int>,
        val draws: Int,
        val shown: Long,
        emissions: Map<String, Int>,
        cpuNs: Long,
        allocatedBytes: Long,
        private val frames: Int,
    ) {
        val seconds = elapsedMs / 1_000.0
        val perSecond: Map<String, Double> = counts.mapValues { it.value / seconds }
        val emissionsPerSecond: Map<String, Double> = emissions.mapValues { it.value / seconds }
        val drawsPerSecond = draws / seconds
        val shownFps = shown / seconds
        private val cpuMsPerSecond = cpuNs / 1e6 / seconds
        private val bytesPerFrame = allocatedBytes / frames.coerceAtLeast(1)

        fun report(): String = buildString {
            appendLine("window draws/s=${"%.2f".format(drawsPerSecond)} shown_fps=${"%.1f".format(shownFps)}")
            appendLine("main thread: cpu ${"%.0f".format(cpuMsPerSecond)} ms/s, allocated ${bytesPerFrame / 1024} KiB per display frame ($frames frames)")
            appendLine("view-model flow emissions/s:")
            emissionsPerSecond.entries.sortedByDescending { it.value }.forEach { (k, v) -> appendLine("  ${"%.1f".format(v)}/s $k") }
            appendLine("recompositions (frames/s):")
            perSecond.entries.filter { it.value >= 2.0 }.sortedByDescending { it.value }.forEach { (k, v) -> appendLine("  ${"%.1f".format(v)}/s $k") }
        }
    }

    /** One flight: the screen composed over [LiveFlight]'s sources, stepped one display frame at a time. */
    private inner class Flight(
        private val scope: TestScope,
        val vm: CockpitViewModel,
        private val live: LiveFlight,
        private val video: SimulatedVideo,
        private val window: () -> android.view.View,
    ) {
        private val frameCanvas = android.graphics.Canvas(android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888))
        private val emissions = HashMap<String, Int>()
        private var counting = false
        var draws = 0
            private set

        init {
            val flows = mapOf(
                "band" to vm.band, "chip" to vm.chip, "flightValues" to vm.flightValues, "flow" to vm.flow,
                "statusItems" to vm.statusItems, "minimap" to vm.minimap, "alerts" to vm.alerts, "preflight" to vm.preflight,
                "freshPilotState" to vm.freshPilotState, "pickerChip" to vm.pickerChip, "videoState" to vm.videoState,
            )
            for ((name, f) in flows) {
                scope.backgroundScope.launch { f.collect { if (counting) emissions[name] = (emissions[name] ?: 0) + 1 } }
            }
        }

        /**
         * One display frame: the sources due now, then the frame. Robolectric runs no render
         * thread, so a frame in which anything invalidated the window is counted here and drawn
         * by hand, which clears the dirty flags for the next frame.
         */
        fun frame() {
            live.step(scope.testScheduler.currentTime)
            scope.testScheduler.advanceTimeBy(DISPLAY_MS)
            scope.runCurrent()
            compose.mainClock.advanceTimeBy(DISPLAY_MS)
            val root = window()
            if (root.isDirty) {
                draws++
                root.draw(frameCanvas)
            }
            counter.endFrame()
        }

        fun fly(ms: Long) {
            val end = scope.testScheduler.currentTime + ms
            while (scope.testScheduler.currentTime < end) frame()
        }

        /** Measures [seconds] of flight. */
        fun measure(seconds: Int): Measurement {
            val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val thread = Thread.currentThread().id
            val shownBefore = video.fanout.shown.frameCount
            val drawsBefore = draws
            val startMs = scope.testScheduler.currentTime
            Composer.setTracer(counter)
            counting = true
            val cpu0 = mx.currentThreadCpuTime
            val alloc0 = mx.getThreadAllocatedBytes(thread)
            var frames = 0
            val end = startMs + seconds * 1_000L
            while (scope.testScheduler.currentTime < end) {
                frame()
                frames++
            }
            val cpu = mx.currentThreadCpuTime - cpu0
            val alloc = mx.getThreadAllocatedBytes(thread) - alloc0
            counting = false
            Composer.setTracer(CompositionCounter.Off)
            return Measurement(
                scope.testScheduler.currentTime - startMs, counter.counts.toMap(), draws - drawsBefore,
                video.fanout.shown.frameCount - shownBefore, emissions.toMap(), cpu, alloc, frames,
            )
        }
    }

    /** Composes the flight screen over a live flight, settles it for 3 s of flight, then runs [block]. */
    private fun inFlight(block: Flight.() -> Unit) {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher, timeout = 5.minutes) {
                val vehicle = FakeVehicle()
                val now = { testScheduler.currentTime }
                val video = SimulatedVideo(VideoClock { testScheduler.currentTime })
                val sessions = VideoSessionManager(setOf(video), backgroundScope)
                val agent = LiveAgent(now)
                val vm = testCockpitVm(vehicle, localApis = agent.apis, videoSelector = VideoSourceSelector(setOf(video)), videoSessions = sessions)
                RedrawTestApp.sessions = sessions
                vehicle.heartbeatAt(now(), armed = true, mode = "GUIDED")
                // The bench's card: the local minimap, which follows the drone's pose.
                vm.autonomy.setMinimapPin(CockpitSettingsRepository.MINIMAP_LOCAL)
                // CockpitRoute marks the flight view shown on start; the beat runs only then.
                vm.setFlightViewShown(true)
                runCurrent()
                val hiltActivity = Robolectric.buildActivity(RedrawTestActivity::class.java).create().get()
                var window: android.view.View? = null
                compose.mainClock.autoAdvance = false
                compose.setContent {
                    window = LocalView.current
                    // The extension slots reach their view models through Hilt's factory, which needs an
                    // activity with a component; a store that already holds empty ones answers them (no
                    // extension contributes anything here).
                    CompositionLocalProvider(LocalContext provides hiltActivity, LocalViewModelStoreOwner provides extensionViewModels(vehicle.id.value)) {
                        AdosTheme {
                            // As the app's root provides it: sheets and menus draw through the window's overlay host.
                            ProvideOverlayHost {
                                CockpitScreen(vm, WidthClass.Expanded, connecting = false, topBar = OverlayTopBar(), onExit = {}, snackbar = {})
                            }
                        }
                    }
                }
                compose.mainClock.advanceTimeByFrame()
                val flight = Flight(this, vm, LiveFlight(vehicle, agent, video), video) { window!! }
                flight.fly(3_000)
                assertTrue("the video plays: ${vm.videoState.value}, main=${vm.video.value.main}", vm.videoState.value is VideoState.Live)
                assertTrue("the pilot is live", vm.pilotState.value != null)
                flight.block()
                vm.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private companion object {
        /** One 60 Hz display frame, as the bench's emulator panel draws. */
        const val DISPLAY_MS = 16L
        const val MAX_PER_SECOND = 10.0

        /** Flows the screen collects that change at most on the 2 Hz tick. */
        val TICK_FLOWS = setOf("band", "chip", "flightValues", "flow", "statusItems", "minimap")
        const val TICK_PER_SECOND = 2.0
        const val PRESSES = 5
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
