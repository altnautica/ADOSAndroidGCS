package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.designsystem.component.overlay.ProvideOverlayHost
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.cockpit.MemoryPreferences
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The ▾ beside the layer-level button lists every layer as a checkbox; a tap toggles that layer. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w914dp-h411dp-land")
class LayerFlagsMenuTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private fun controller() = LayerController(
        scope, MutableStateFlow(DeviceId("drone-1")), CockpitSettingsRepository(MemoryPreferences()),
        engine = object : VisionEngine {
            override suspend fun demand(node: DeviceId, body: JsonObject) = Unit
            override suspend fun status(node: DeviceId): JsonObject? = null
            override suspend fun setDetector(node: DeviceId, modelId: String) = false
        },
        now = { 0L },
        previewAllowed = { true },
        armed = { false },
    ).apply {
        useSpec(
            LayerSpec.parse(
                File("src/main/assets/cockpit-spec/layers.json").readText(),
                File("src/main/assets/cockpit-spec/views.json").readText(),
            )!!,
        )
    }

    @Test
    fun theFlagsButtonListsEveryCanvasLayerAndTogglesOne() {
        val c = controller()
        compose.setContent {
            AdosTheme {
                ProvideOverlayHost {
                    CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) { LayerLevelButton(c) }
                }
            }
        }
        compose.onNodeWithTag("layer-flags", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        // Every layer the canvas draws, by its spec name.
        for (name in listOf("Depth", "Surfaces", "Obstacles", "Targets", "Route", "Motion")) {
            compose.onNodeWithText(name).assertIsDisplayed()
        }
        assertEquals(6, c.spec.canvasOrder.size)
        assertFalse(c.flags.value.isOn(LayerSpec.DEPTH))
        compose.onNodeWithText("Depth").performClick()
        compose.waitForIdle()
        assertTrue(c.flags.value.isOn(LayerSpec.DEPTH))
    }

    @Test
    fun theLevelButtonStillCyclesAndNamesTheLevelFromTheSpec() {
        val c = controller()
        compose.setContent {
            AdosTheme {
                ProvideOverlayHost {
                    CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) { LayerLevelButton(c) }
                }
            }
        }
        val before = c.flags.value.level
        // The layers icon and the level name; the word "Layers" lives only in the description.
        compose.onNodeWithText(specLevel(before)).assertIsDisplayed()
        compose.onNodeWithTag("layer-level-icon", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Layers · ", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("layer-level", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(before.cycle(1), c.flags.value.level)
    }

    private fun specLevel(level: LayerLevel): String = when (level) {
        LayerLevel.Instruments -> "Instruments"
        LayerLevel.Full -> "Full"
        LayerLevel.Route -> "Route"
        LayerLevel.Off -> "Off"
    }
}
