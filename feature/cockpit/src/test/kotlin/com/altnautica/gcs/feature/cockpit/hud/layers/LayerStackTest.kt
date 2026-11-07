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
}
