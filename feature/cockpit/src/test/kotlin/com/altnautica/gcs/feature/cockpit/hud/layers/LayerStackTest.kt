package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.management.ManagementFactory
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayerStackTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val demands = ArrayList<JsonObject>()
    private var clock = 10_000L

    @After
    fun tearDown() = scope.cancel()

    private fun controller(): LayerController {
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "t.preferences_pb") }
        return LayerController(
            scope, MutableStateFlow(DeviceId("demo")), CockpitSettingsRepository(store),
            engine = object : VisionEngine {
                override suspend fun demand(node: DeviceId, body: JsonObject) {
                    demands += body
                }
                override suspend fun status(node: DeviceId): JsonObject? = null
                override suspend fun setDetector(node: DeviceId, modelId: String) = false
            },
            now = { clock },
            previewAllowed = { true },
            armed = { false },
        )
    }

    private fun arText() = javaClass.classLoader!!.getResource("fixtures/ar-frame.json")!!.readText()

    @Test
    fun `streaming frames redraws without recomposing and holds the leases`() {
        val c = controller()
        val frame = LayerParse.ar(arText())
        val outbound = ArrayList<String>()
        scope.launch { c.outbound.collect { outbound += it } }
        // The layer loop runs every display frame while items move; drive the clock by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent { LayerStack(c, "main", 1280f, 720f, Modifier.size(640.dp, 360.dp)) }
        compose.mainClock.advanceTimeBy(100)
        val before = LayerStackDebug.compositions.get()
        val drawsBefore = LayerStackDebug.drawCount()
        repeat(40) { i ->
            clock += 50
            compose.runOnUiThread {
                c.store.push(LayerFrame.Ar(frame.copy(tsMs = frame.tsMs + i)), clock)
            }
            compose.mainClock.advanceTimeBy(50)
        }
        assertEquals(before, LayerStackDebug.compositions.get())
        // Rendering runs the draw pass (layers, interpolation, labels), still without recomposing.
        compose.onRoot().captureToImage()
        assertTrue(LayerStackDebug.drawCount() > drawsBefore)
        assertEquals(before, LayerStackDebug.compositions.get())
        assertTrue(outbound.any { it.contains("\"type\":\"view\"") && it.contains("\"camera_id\":\"main\"") })
        assertTrue(demands.any { it["camera_id"].toString() == "\"main\"" && !it.containsKey("kind") })
    }

    /** A targets layer whose batch age and motion the test sets; records each draw's alpha. */
    private class FakeTargets(var receivedAt: Long?, var moving: Boolean = false) : CanvasTargets {
        val alphas = ArrayList<Float>()
        var steps = 0
        override fun receivedAtMs() = receivedAt
        override fun step(nowMs: Long): Boolean {
            steps++
            return moving
        }
        override fun DrawScope.draw(alpha: Float) {
            alphas += alpha
        }
    }

    @Test
    fun `targets dim at 500 ms and hide at 2 s like every other layer`() {
        val c = controller()
        val t = FakeTargets(receivedAt = clock)
        compose.mainClock.autoAdvance = false
        compose.setContent { LayerStack(c, "main", 1280f, 720f, Modifier.size(640.dp, 360.dp), targets = t) }
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().captureToImage()
        assertEquals(1f, t.alphas.last())

        clock += 800
        compose.mainClock.advanceTimeBy(200)
        compose.onRoot().captureToImage()
        assertEquals("a batch 800 ms old draws dimmed", 0.5f, t.alphas.last())

        clock += 1_500
        compose.mainClock.advanceTimeBy(200)
        val before = t.alphas.size
        compose.onRoot().captureToImage()
        assertEquals("a batch 2.3 s old is not drawn", before, t.alphas.size)
    }

    @Test
    fun `moving targets are stepped by the canvas loop and redraw without recomposing`() {
        val c = controller()
        val t = FakeTargets(receivedAt = clock, moving = true)
        compose.mainClock.autoAdvance = false
        compose.setContent { LayerStack(c, "main", 1280f, 720f, Modifier.size(640.dp, 360.dp), targets = t) }
        compose.mainClock.advanceTimeBy(100)
        val compositions = LayerStackDebug.compositions.get()
        val ticks = LayerStackDebug.ticks.get()
        repeat(30) {
            clock += 16
            t.receivedAt = clock
            compose.mainClock.advanceTimeBy(16)
        }
        assertTrue("steps ${t.steps}", t.steps >= 25)
        assertTrue(LayerStackDebug.ticks.get() - ticks >= 25)
        assertEquals(compositions, LayerStackDebug.compositions.get())
    }

    private fun ticksOverOneSecond(presentation: LayerPresentation): Long {
        val c = controller()
        val t = FakeTargets(receivedAt = clock, moving = true)
        compose.mainClock.autoAdvance = false
        compose.setContent { LayerStack(c, "main", 1280f, 720f, Modifier.size(640.dp, 360.dp), targets = t, presentation = presentation) }
        compose.mainClock.advanceTimeBy(200)
        val start = LayerStackDebug.ticks.get()
        repeat(60) {
            clock += 16
            t.receivedAt = clock
            compose.mainClock.advanceTimeBy(16)
        }
        return LayerStackDebug.ticks.get() - start
    }

    @Test
    fun `the tv class caps the overlay at 30 fps`() {
        val capped = ticksOverOneSecond(LayerPresentation(fpsCap = 30))
        assertTrue("capped at 30 fps: $capped redraws in ~1 s", capped in 25..33)
    }

    @Test
    fun `other classes redraw at the display rate`() {
        val free = ticksOverOneSecond(LayerPresentation())
        assertTrue("uncapped: $free redraws in ~1 s", free >= 50)
    }

    @Test
    fun `a steady obstacles and route draw allocates almost nothing`() {
        val c = controller()
        val obstacles = LayerParse.obstacles(javaClass.classLoader!!.getResource("fixtures/obstacles-frame.json")!!.readText())
        compose.runOnUiThread {
            c.store.push(LayerFrame.Obstacles(obstacles), clock)
            c.store.push(LayerFrame.Ar(LayerParse.ar(arText())), clock)
        }
        val density = Density(2f)
        val canvas = Canvas(ImageBitmap(640, 360))
        val scope = CanvasDrawScope()
        val measurer = TextMeasurer(createFontFamilyResolver(ApplicationProvider.getApplicationContext()), density, LayoutDirection.Ltr)
        val layerCanvas = LayerCanvas(LayerPainter(measurer), LayerLabels("ar", "obstacles", "depth", "surfaces", "targets", "unscaled", "approx", "reduced"))
        val flags = c.flags.value
        assertTrue(flags.isOn(LayerSpec.OBSTACLES) && flags.isOn(LayerSpec.AR_ROUTE))
        fun drawOnce() = scope.draw(density, LayoutDirection.Ltr, canvas, Size(640f, 360f)) {
            with(layerCanvas) { draw(c, "main", 1280f, 720f, flags, DepthOptions(), LayerPresentation(), null) }
        }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        compose.runOnUiThread {
            repeat(20) { drawOnce() } // warm up: label layouts, strokes, decoded grids
            val tid = Thread.currentThread().id
            val t0 = bean.getThreadAllocatedBytes(tid)
            repeat(200) { drawOnce() }
            val perDraw = (bean.getThreadAllocatedBytes(tid) - t0) / 200
            // Robolectric's shadow canvas allocates on its own (1.4 to 2.5 KiB a draw measured);
            // this bound sits above that floor and below a draw that rebuilds paths, strokes or
            // label layouts each frame.
            assertTrue("steady draw allocated $perDraw bytes per draw", perDraw < 8_192)
        }
    }
}
