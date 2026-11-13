package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
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
        compose.setContent { CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) { LayerStack(c, "main", 1280f, 720f, Modifier.size(640.dp, 360.dp)) } }
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
        compose.setContent { CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) { LayerStack(c, "main", 1280f, 720f, Modifier.size(640.dp, 360.dp), targets = t) } }
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
    fun `moving targets are stepped on the beat and redraw without recomposing`() {
        val c = controller()
        val t = FakeTargets(receivedAt = clock, moving = true)
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) { LayerStack(c, "main", 1280f, 720f, Modifier.size(640.dp, 360.dp), targets = t) } }
        compose.mainClock.advanceTimeBy(100)
        val compositions = LayerStackDebug.compositions.get()
        val ticks = LayerStackDebug.ticks.get()
        val steps = t.steps
        // 30 display frames, 480 ms: four or five beats, so four or five steps and redraws.
        repeat(30) {
            clock += 16
            t.receivedAt = clock
            compose.mainClock.advanceTimeBy(16)
        }
        assertTrue("steps ${t.steps - steps}", t.steps - steps in 4..5)
        assertTrue(LayerStackDebug.ticks.get() - ticks in 4..5)
        assertEquals(compositions, LayerStackDebug.compositions.get())
    }

    private fun ticksOverOneSecond(presentation: LayerPresentation, push: LayerController.() -> Unit = {}): Long {
        val c = controller()
        val t = FakeTargets(receivedAt = clock, moving = true)
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) { LayerStack(c, "main", 1280f, 720f, Modifier.size(640.dp, 360.dp), targets = t, presentation = presentation) } }
        compose.mainClock.advanceTimeBy(200)
        val start = LayerStackDebug.ticks.get()
        repeat(60) {
            clock += 16
            t.receivedAt = clock
            c.push()
            compose.mainClock.advanceTimeBy(16)
        }
        return LayerStackDebug.ticks.get() - start
    }

    @Test
    fun `moving layers and a 60 Hz frame stream redraw once a beat, not once a frame`() {
        val ar = LayerParse.ar(arText())
        var n = 0L
        val ticks = ticksOverOneSecond(LayerPresentation()) { store.push(LayerFrame.Ar(ar.copy(tsMs = ar.tsMs + n++)), clock) }
        assertTrue("$ticks redraws in ~1 s", ticks in 9..10)
    }

    @Test
    fun `the tv class's 30 fps cap never redraws faster than the beat`() {
        val capped = ticksOverOneSecond(LayerPresentation(fpsCap = 30))
        assertTrue("$capped redraws in ~1 s", capped in 9..10)
    }

    @Test
    fun `a cap below the beat skips whole beats`() {
        assertEquals(1L, beatsPerRedraw(30))
        assertEquals(1L, beatsPerRedraw(10))
        assertEquals(2L, beatsPerRedraw(5))
        assertEquals(4L, beatsPerRedraw(3))
        val capped = ticksOverOneSecond(LayerPresentation(fpsCap = 5))
        assertTrue("$capped redraws in ~1 s", capped in 4..5)
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

    @Test
    fun `a hidden layer shows its unavailable chip clear of the chrome`() {
        val c = controller()
        val obstacles = LayerParse.obstacles(javaClass.classLoader!!.getResource("fixtures/obstacles-frame.json")!!.readText())
        compose.runOnUiThread { c.store.push(LayerFrame.Obstacles(obstacles), clock) }
        val density = Density(2f)
        val measurer = TextMeasurer(createFontFamilyResolver(ApplicationProvider.getApplicationContext()), density, LayoutDirection.Ltr)
        val layerCanvas = LayerCanvas(LayerPainter(measurer), LayerLabels("ar", "Obstacles unavailable", "depth", "surfaces", "targets", "unscaled", "approx", "reduced"))
        val flags = c.flags.value.copy(on = setOf(LayerSpec.OBSTACLES))
        // The chrome covers the top 30 dp, the left 40 dp and the bottom 40 dp of the 320 x 180 dp video.
        val presentation = LayerPresentation(chipInset = androidx.compose.foundation.layout.PaddingValues(start = 40.dp, top = 30.dp, bottom = 40.dp))
        fun inked(): List<Pair<Int, Int>> {
            val bitmap = ImageBitmap(640, 360)
            compose.runOnUiThread {
                CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(640f, 360f)) {
                    with(layerCanvas) { draw(c, "main", 1280f, 720f, flags, DepthOptions(), presentation, null) }
                }
            }
            val px = IntArray(640 * 360)
            bitmap.readPixels(px)
            return px.indices.filter { (px[it] ushr 24) != 0 }.map { (it % 640) to (it / 640) }
        }
        // Past the 2 s hide age the cells are gone and only the chip is drawn.
        clock += 2_300
        val chip = inked()
        assertTrue("the obstacles unavailable chip is drawn", chip.size > 200)
        assertTrue("the chip stays right of the left chrome", chip.all { it.first >= 80 })
        assertTrue("the chip stays below the top chrome", chip.all { it.second >= 60 })
        assertTrue("the chip stays above the bottom chrome", chip.all { it.second <= 280 })
    }
}
