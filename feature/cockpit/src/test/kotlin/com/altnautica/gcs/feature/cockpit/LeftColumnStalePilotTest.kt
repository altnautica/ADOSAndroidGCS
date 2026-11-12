package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.chrome.Chrome
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The left column decides Take off or Land from a fresh pilot frame only: once the pilot
 * stalls on a disarmed, landed frame and the flight controller reports armed, it offers Land.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w800dp-h400dp")
class LeftColumnStalePilotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a stale disarmed pilot frame with an armed heartbeat shows Land, not Take off`() {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val vehicle = FakeVehicle()
                val agent = FakeAutonomyAgent(this, """{"owner":"idle","armed":false,"landed":true}""")
                val vm = testCockpitVm(vehicle, agent.apis)
                vehicle.heartbeatAt(testScheduler.currentTime, armed = false)
                advanceTimeBy(300)
                runCurrent()
                // The pilot stalls on its disarmed frame while the drone is armed and flown on the FC.
                agent.pilotAnswers = false
                var left = 3_000L
                while (left > 0) {
                    vehicle.heartbeatAt(testScheduler.currentTime, armed = true)
                    advanceTimeBy(250)
                    runCurrent()
                    left -= 250
                }
                compose.setContent {
                    AdosTheme {
                        CompositionLocalProvider(
                            LocalCockpitStrings provides vm.specStrings,
                            LocalChrome provides remember { Chrome.from(vm.cockpitSpec.tokens.chrome) },
                        ) {
                            Box(Modifier.size(80.dp, 300.dp)) { LeftColumn(vm, 56.dp, 8.dp) }
                        }
                    }
                }
                compose.waitForIdle()
                compose.onNodeWithTag("grid-land", useUnmergedTree = true).assertExists()
                compose.onNodeWithTag("grid-takeoff", useUnmergedTree = true).assertDoesNotExist()
                vm.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
