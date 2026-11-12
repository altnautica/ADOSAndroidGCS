package com.altnautica.gcs.feature.settings.controller

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.skills.binding.Ds4Bindings
import com.altnautica.gcs.core.skills.controls.BindingProblem
import com.altnautica.gcs.core.skills.controls.ControlsRepository
import com.altnautica.gcs.core.skills.controls.EditRefusal
import com.altnautica.gcs.core.skills.controls.EditResult
import com.altnautica.gcs.core.skills.controls.Gesture
import com.altnautica.gcs.core.skills.controls.SaveResult
import com.altnautica.gcs.core.skills.controls.StickMode
import com.altnautica.gcs.core.skills.controls.setRowAction
import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.core.skills.input.PadSnapshot
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.core.skills.spec.ControlBindingSpec
import com.altnautica.gcs.core.skills.spec.DirectorySpecSource
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performScrollTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The controller configuration cases: live highlight, persisted rebinding, conflicts, locked bindings. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ControlsConfigTest {
    @get:Rule
    val compose = createComposeRule()

    private val spec = CockpitSpecRepository(DirectorySpecSource(File("../cockpit/src/main/assets/cockpit-spec")))
    private val ds4 get() = spec.ds4
    private val shipped: List<ControlBindingSpec> get() = Ds4Bindings.resolve(ds4, null, emptyList()).rows

    private val noActions = object : ControlsActions {
        override fun openControl(control: String) = Unit
        override fun closeControl() = Unit
        override fun setAction(row: ControlBindingSpec, action: String) = Unit
        override fun addBinding(control: String, gesture: String, action: String) = Unit
        override fun save() = Unit
        override fun discard() = Unit
        override fun resetToDefault() = Unit
        override fun setStickMode(mode: StickMode) = Unit
        override fun setDeadzone(value: Float) = Unit
        override fun startPress() = Unit
        override fun endPress() = Unit
        override fun pressedOnScreen(control: String) = Unit
        override fun startCalibration() = Unit
        override fun closeCalibration() = Unit
        override fun clearCalibration() = Unit
        override fun testRumble() = Unit
        override fun testLightbar() = Unit
        override fun toggleStickTest() = Unit
        override fun exportFile() = Unit
        override fun importFile() = Unit
        override fun nextTourStep() = Unit
        override fun finishTour() = Unit
        override fun setOnScreenSticks(on: Boolean) = Unit
    }

    private fun stateFor(rows: List<ControlBindingSpec>, dirty: Boolean): ControlsUiState {
        val resolved = Ds4Bindings.resolve(ds4, rows, emptyList())
        return ControlsUiState(
            loaded = true,
            rows = resolved.rows,
            conflicts = resolved.conflicts,
            missingLocked = Ds4Bindings.missingLocked(ds4, resolved.rows),
            dirty = dirty,
            deadzone = ds4.conditioning.radialDeadzone,
        )
    }

    private fun pad(buttons: Int) = PadSnapshot(1, 0x054C, 0x09CC, GamepadAxes.Neutral, GamepadAxes.Neutral, buttons, 1L, emptyList())

    private fun hasState(text: String) = SemanticsMatcher("stateDescription contains $text") { node ->
        node.config.getOrElse(SemanticsProperties.StateDescription) { "" }.contains(text)
    }

    @Test
    fun pressingCrossHighlightsItsHotspotWithinOneFrame() {
        val input = mutableStateOf(pad(0))
        compose.setContent {
            AdosTheme { ControlsScreen(stateFor(shipped, dirty = false), input, spec, noActions, { it }) }
        }
        compose.onNodeWithTag("hotspot-cross").assert(hasState("pressed").not())
        input.value = pad(1 shl ds4.stream.buttonsBitfield.getValue("cross"))
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("hotspot-cross").assert(hasState("pressed"))
    }

    @Test
    fun rebindingSquareToRecordPersistsAcrossReload(): Unit = runBlocking {
        val dir = Files.createTempDirectory("controls").toFile()
        val file = File(dir, "controls.preferences_pb")
        val scope1 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val first = ControlsRepository(PreferenceDataStoreFactory.create(scope = scope1) { file }, spec, flowOf(emptyList()))
        val rows = first.bindings.first()
        val square = rows.first { it.control == "square" && it.gesture == Gesture.TAP && it.with.isEmpty() }
        val edited = (setRowAction(rows, square, "record_toggle", Ds4Bindings.stopHoldMs(spec.layout)) as EditResult.Applied).rows
        assertEquals(SaveResult.Saved, first.saveBindings(edited))
        // Join, not just cancel: the file stays registered as active until the first store's job completes.
        scope1.coroutineContext[Job]!!.cancelAndJoin()

        // A new repository on the same file: what the app reads after a restart.
        val scope2 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val second = ControlsRepository(PreferenceDataStoreFactory.create(scope = scope2) { file }, spec, flowOf(emptyList()))
        val reloaded = second.bindings.first().first { it.control == "square" && it.gesture == Gesture.TAP && it.with.isEmpty() }
        assertEquals("record_toggle", reloaded.action)
        scope2.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun aSecondBindingOnTheSameChordOutlinesBothControlsAndBlocksSave() {
        val arm = shipped.first { it.action == "arm" }
        // The same L1 + R1 hold, now bound to a second action: both rows fire on one press.
        val duplicate = arm.copy(action = "photo", locked = false)
        val state = stateFor(shipped + duplicate, dirty = true)
        assertTrue(state.conflicts.isNotEmpty())
        val idle = mutableStateOf(pad(0))
        compose.setContent {
            AdosTheme { ControlsScreen(state, idle, spec, noActions, { it }) }
        }
        compose.onNodeWithTag("hotspot-l1").assert(hasState("conflict"))
        compose.onNodeWithTag("hotspot-r1").assert(hasState("conflict"))
        compose.onNodeWithTag("controls-conflict").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
        compose.onNodeWithTag("controls-save").assertIsNotEnabled()
    }

    @Test
    fun removingPauseIsRefused(): Unit = runBlocking {
        val pause = shipped.first { it.action == "pause" && it.locked }
        // The editor refuses to clear a locked row.
        val edit = setRowAction(shipped, pause, "none", Ds4Bindings.stopHoldMs(spec.layout))
        assertEquals(EditResult.Refused(EditRefusal.Locked), edit)

        // A list without it (an imported file, say) is never written.
        val dir = Files.createTempDirectory("controls").toFile()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val repo = ControlsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "c.preferences_pb") }, spec, flowOf(emptyList()))
        val result = repo.saveBindings(shipped - pause)
        assertTrue(result is SaveResult.Refused)
        assertTrue((result as SaveResult.Refused).problems.any { it is BindingProblem.MissingLocked && it.locked.action == "pause" })
        assertEquals(shipped.count { it.action == "pause" }, repo.bindings.first().count { it.action == "pause" })
        scope.cancel()
        dir.deleteRecursively()
    }

    private val padInfo = com.altnautica.gcs.core.skills.input.controller.ControllerInfo(
        deviceId = 1,
        name = "Wireless Controller",
        vendorId = 0x054C,
        productId = 0x09CC,
        family = com.altnautica.gcs.core.skills.input.controller.ControllerFamily.PlayStation,
        batteryPct = null,
        charging = false,
    )

    @Test
    fun hotspotsAreFullTouchTargetsAndSpeakTheControlName() {
        compose.setContent {
            AdosTheme { ControlsScreen(stateFor(shipped, dirty = false), remember { mutableStateOf(pad(0)) }, spec, noActions, { it }) }
        }
        listOf("l1", "r1", "share", "options", "dpad_up").forEach { id ->
            val b = compose.onNodeWithTag("hotspot-$id").fetchSemanticsNode().boundsInRoot
            with(compose.density) {
                assertTrue("$id width ${b.width.toDp()}", b.width.toDp() >= 47.5.dp)
                assertTrue("$id height ${b.height.toDp()}", b.height.toDp() >= 47.5.dp)
            }
        }
        val desc = compose.onNodeWithTag("hotspot-dpad_up").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(desc, desc.startsWith("D-pad up: "))
    }

    @Test
    fun tappingALabelOpensItsControl() {
        var opened: String? = null
        val actions = object : ControlsActions by noActions {
            override fun openControl(control: String) {
                opened = control
            }
        }
        compose.setContent {
            AdosTheme { ControlsScreen(stateFor(shipped, dirty = false), remember { mutableStateOf(pad(0)) }, spec, actions, { it }) }
        }
        compose.onNodeWithTag("label-cross").performClick()
        assertEquals("cross", opened)
    }

    @Test
    fun stickTestShowsTheReadoutAndAStopButton() {
        val state = stateFor(shipped, dirty = false).copy(controller = padInfo, test = HardwareTest.Sticks)
        compose.setContent {
            AdosTheme { ControlsScreen(state, remember { mutableStateOf(pad(0)) }, spec, noActions, { it }) }
        }
        compose.onNodeWithTag("controls-stick-readout").assertExists()
        compose.onNodeWithTag("controls-test-sticks").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test
    fun savedCalibrationShowsDoneAndTheCalibratedBadge() {
        val state = stateFor(shipped, dirty = false).copy(
            controller = padInfo,
            calibrated = true,
            calibration = CalibrationUi(CalibrationPhase.Saved, 1f),
        )
        compose.setContent {
            AdosTheme { ControlsScreen(state, remember { mutableStateOf(pad(0)) }, spec, noActions, { it }) }
        }
        compose.onNodeWithTag("controls-calibration-done").assertExists()
        compose.onNodeWithTag("controls-calibrated").assertExists()
    }

    @Test
    fun aCalibratedPadOffersClearCalibration() {
        var cleared = false
        val actions = object : ControlsActions by noActions {
            override fun clearCalibration() {
                cleared = true
            }
        }
        val state = stateFor(shipped, dirty = false).copy(controller = padInfo, calibrated = true)
        compose.setContent {
            AdosTheme { ControlsScreen(state, remember { mutableStateOf(pad(0)) }, spec, actions, { it }) }
        }
        compose.onNodeWithTag("controls-calibration-clear").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(cleared)
    }
}
