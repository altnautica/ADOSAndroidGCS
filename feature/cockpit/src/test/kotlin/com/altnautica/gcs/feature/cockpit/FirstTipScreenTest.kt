package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.skills.tips.TipsEngine
import com.altnautica.gcs.core.skills.tips.TipsRepository
import com.altnautica.gcs.feature.cockpit.chrome.Chrome
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome
import com.altnautica.gcs.feature.cockpit.chrome.TopBarOverflow
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import com.altnautica.gcs.feature.cockpit.tips.LocalTipAnchors
import com.altnautica.gcs.feature.cockpit.tips.TAG_TIP
import com.altnautica.gcs.feature.cockpit.tips.TAG_TIP_DISMISS
import com.altnautica.gcs.feature.cockpit.tips.TipAnchorRegistry
import com.altnautica.gcs.feature.cockpit.tips.TipLayer
import com.altnautica.gcs.feature.cockpit.tips.tipAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The flight screen's first tip, on the real top bar and status pill a cockpit view model
 * drives: on a disarmed vehicle the pill tip shows after the first delay, below the top bar
 * (a never-cover zone) and under the pill, and its button marks it seen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w800dp-h400dp")
class FirstTipScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the first tip is the status pill tip beside the real top bar`() {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val vehicle = FakeVehicle()
                vehicle.heartbeatAt(testScheduler.currentTime, armed = false)
                val vm = testCockpitVm(vehicle)
                runCurrent()
                val repository = TipsRepository(MemoryPreferences())
                compose.mainClock.autoAdvance = false
                compose.setContent {
                    AdosTheme {
                        CompositionLocalProvider(
                            LocalCockpitStrings provides vm.specStrings,
                            LocalChrome provides remember { Chrome.from(vm.cockpitSpec.tokens.chrome) },
                            LocalTipAnchors provides remember { TipAnchorRegistry() },
                        ) {
                            val status = remember { StatusListState() }
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxWidth().height(44.dp).tipAnchor("top_bar")) {
                                    FlightTopBar(
                                        vm = vm,
                                        width = WidthClass.Expanded,
                                        overflow = remember { TopBarOverflow() },
                                        onExit = {},
                                        onMenuOpen = { status.menuOpen = it },
                                        pill = { FlightStatusPill(vm, status) },
                                    )
                                }
                                TipLayer(tipFacts(vm, status), repository, TipsEngine(vm.cockpitSpec.tips), nowMs = { compose.mainClock.currentTime })
                            }
                        }
                    }
                }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(2_000)
                compose.waitForIdle()
                compose.onNodeWithTag(TAG_TIP).assertDoesNotExist()
                compose.mainClock.advanceTimeBy(700)
                compose.waitForIdle()
                compose.onNodeWithTag(TAG_TIP).assertIsDisplayed()

                fun bounds(vararg tags: String) = tags.firstNotNullOf { tag ->
                    compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
                }
                val tip = bounds(TAG_TIP)
                val bar = bounds("tip-anchor-top_bar")
                // The pill carries its own tag and the tip anchor's; either names the same node.
                val pill = bounds("status-pill", "tip-anchor-status_pill")
                assertTrue("the tip stays off the top bar: $tip vs $bar", tip.top >= bar.bottom)
                assertTrue("the tip sits under the pill: $tip vs $pill", tip.left < pill.right && pill.left < tip.right)

                compose.onNodeWithTag(TAG_TIP_DISMISS).performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.onNodeWithTag(TAG_TIP).assertDoesNotExist()
                assertEquals(listOf("status_pill"), runBlocking { repository.state.first().seen })
                vm.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
