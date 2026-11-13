package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.LocalPosition
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.feature.cockpit.FakeVehicle
import com.altnautica.gcs.feature.cockpit.autonomy.PoseFrame
import com.altnautica.gcs.feature.cockpit.autonomy.PoseHealth
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.heartbeatAt
import com.altnautica.gcs.feature.cockpit.testCockpitVm
import com.altnautica.gcs.feature.cockpit.testSpec
import kotlin.math.cos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A long-press on the local minimap while the SLAM pose is fresh: the drone is drawn at its
 * SLAM pose (map frame), the flight controller flies its own local frame, so the press is
 * handed on as an offset from the drone as drawn and flown from the drone's position.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LocalMinimapFlyHereTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a press 10 m north of the drone drawn at its SLAM pose flies 10 m north of the drone`() {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val vehicle = FakeVehicle()
                vehicle.heartbeatAt(testScheduler.currentTime, armed = true, mode = "GUIDED")
                vehicle.telemetry.position.value = PositionFix(47.0, 8.0, 412f, 12f, 0f, 0f, 0f, testScheduler.currentTime)
                // The flight controller's local frame and the SLAM map frame disagree by tens of metres.
                val fcLocal = LocalPosition(3f, -2f, -12f, 0f, 0f, 0f, testScheduler.currentTime)
                vehicle.telemetry.localPosition.value = fcLocal
                val vm = testCockpitVm(vehicle)
                advanceTimeBy(600)
                runCurrent()

                val pose = PoseFrame(50f, 50f, 0f, PoseHealth.Good, receivedAtMs = 1_000L)
                val inputs = LocalMapInputs(pose, fcLocal, null, emptyList(), null, null, null, nowMs = 1_000L, plan = null)
                val presses = mutableListOf<Pair<Float, Float>>()
                compose.setContent {
                    AdosTheme {
                        CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                            Box(Modifier.size(300.dp)) {
                                LocalMinimap({ inputs }, "auto", {}, Modifier.size(300.dp), onLongPress = { n, e -> presses += n to e })
                            }
                        }
                    }
                }
                // The default scale is 10 px a metre: 100 px above the centre is 10 m north of the drone.
                compose.onNodeWithContentDescription(testSpec.setupStrings.format("minimap.description"))
                    .performTouchInput { longClick(center - Offset(0f, 100f)) }
                compose.waitForIdle()
                val (north, east) = presses.single()
                assertEquals(10f, north, 0.05f)
                assertEquals(0f, east, 0.05f)

                vm.pickLocalPoint(north, east)
                runCurrent()
                vm.confirm.onTouchHold(true)
                advanceTimeBy(1_000)
                runCurrent()
                vm.confirm.onTouchHold(false)
                runCurrent()
                val fly = vehicle.control.sent.single() as VehicleCommand.FlyTo
                assertEquals(47.0 + 10.0 / 111_319.49, fly.latDeg, 1e-7)
                assertEquals(8.0, fly.lonDeg, 1e-7)
                vm.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
