package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.skills.hud.PillLinkLoss
import com.altnautica.gcs.feature.cockpit.FakeVehicle
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.heartbeatAt
import com.altnautica.gcs.feature.cockpit.pilot.PillFacts
import com.altnautica.gcs.feature.cockpit.pilotState
import com.altnautica.gcs.feature.cockpit.preflight.PreflightResult
import com.altnautica.gcs.feature.cockpit.testCockpitVm
import com.altnautica.gcs.feature.cockpit.testSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The pill never reads all systems OK before anything is known, and never while the pilot
 * counts down to its link-loss action.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StatusPillWaitingTest {
    @get:Rule
    val compose = createComposeRule()

    private val rules = specStatusPill()

    @Test
    fun `before a pilot frame or a heartbeat the pill reads no link, and the first heartbeat ends that`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val vehicle = FakeVehicle()
                val vm = testCockpitVm(vehicle)
                backgroundScope.launch { vm.statusWaiting.collect {} }
                runCurrent()
                assertTrue("nothing has arrived yet", vm.statusWaiting.value)
                vehicle.heartbeatAt(testScheduler.currentTime, armed = false)
                runCurrent()
                assertFalse("a heartbeat is something known", vm.statusWaiting.value)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `the waiting face shows the neutral no-link text, and any item replaces it`() {
        assertTrue(StatusModel.pill(emptyList(), emptySet(), waiting = true).waiting)
        val item = StatusItem("storage", StatusSource.Storage, StatusSeverity.Warning, text = "95 % used")
        assertFalse("an active item is shown, not the waiting face", StatusModel.pill(listOf(item), emptySet(), waiting = true).waiting)
        compose.setContent {
            CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                StatusPill(StatusModel.pill(emptyList(), emptySet(), waiting = true), onOpen = {})
            }
        }
        compose.onNodeWithText("No link").assertExists()
        compose.onNodeWithText("All systems OK").assertDoesNotExist()
    }

    @Test
    fun `a fresh pilot counting down to its link-loss action is one critical item worded as the banner`() {
        val facts = PillFacts(pilotRule = true, pilotAgeMs = 100, linkLoss = PillLinkLoss(6.2f, "rth"))
        val items = StatusModel.items(emptyList(), PreflightResult(emptyList()), emptyList(), facts, rules)
        assertEquals(listOf("link_loss"), items.map { it.key })
        assertEquals(StatusSeverity.Critical, items[0].severity)
        assertEquals(StatusSource.Pilot, items[0].source)
        assertEquals("Link lost: Return home in 7 s", items[0].text)
        assertEquals(StatusSeverity.Critical, StatusModel.pill(items, emptySet()).severity)
    }

    @Test
    fun `a stale pilot's countdown is not shown, only that the pilot does not answer`() {
        val facts = PillFacts(pilotRule = true, pilotAgeMs = 5_000, linkLoss = PillLinkLoss(6.2f, "rth"))
        val items = StatusModel.items(emptyList(), PreflightResult(emptyList()), emptyList(), facts, rules)
        assertEquals(listOf("pilot_not_answering"), items.map { it.key })
    }

    @Test
    fun `the countdown comes from the pilot frame, its action from the frame or the banner`() {
        val p = pilotState(PilotOwner.Pilot, armed = true, landed = false, receivedAtMs = 0L)
        assertNull(PillLinkLoss.of(p))
        assertEquals(PillLinkLoss(4f, "land"), PillLinkLoss.of(p.copy(linkLossInS = 4f, linkLossAction = "land")))
    }
}
