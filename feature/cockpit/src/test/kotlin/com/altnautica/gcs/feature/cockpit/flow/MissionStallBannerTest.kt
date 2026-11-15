package com.altnautica.gcs.feature.cockpit.flow

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.agent.pilot.PilotWire
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.MissionStallKind
import com.altnautica.gcs.core.model.PilotBanner
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.feature.cockpit.BANNER_HEIGHT
import com.altnautica.gcs.feature.cockpit.testSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** The persistent stall overlay, read from the agent's golden stall frame. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MissionStallBannerTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = testSpec.setupStrings

    /** The agent's golden pilot frame with a mission stall (tests run in the module directory). */
    private val golden: PilotState = checkNotNull(
        PilotWire.parseState(Json.parseToJsonElement(File("../../core/agent/src/test/resources/fixtures/pilot-frame-stall.json").readText()) as JsonObject, 0L),
    )

    private fun show(stall: StallUi, sent: MutableList<String>) {
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(LocalCockpitStrings provides strings) {
                    MissionStallBanner(stall, onAction = { sent += it.action }, modifier = Modifier.widthIn(max = 520.dp))
                }
            }
        }
    }

    @Test
    fun `the golden stall reads its waypoint, window and countdown rounded up, and hides the duplicate pilot banner`() {
        val flow = FlowUi.of(golden, "android", streaming = false)
        val stall = checkNotNull(flow.stall)
        assertEquals(StallUi(MissionStallKind.Mission, "3", 15, 33), stall)
        assertEquals("No progress to waypoint 3 for 15 s · Skipping in 33 s", stall.title(strings))
        assertEquals(listOf(StallAction.Skip, StallAction.Return, StallAction.Land), stall.actions)
        assertNull("the stall banner's words are already in the overlay", flow.banner)
        val skipped = PilotBanner("pilot.banner.mission_wp_skipped", mapOf("wp" to "2"), null)
        assertEquals(skipped, FlowUi.of(golden.copy(banner = skipped), "android", streaming = false).banner)
        assertEquals("with no stall the pilot banner shows", golden.banner, FlowUi.of(golden.copy(missionStall = null), "android", streaming = false).banner)
        assertNull(FlowUi.of(golden.copy(missionStall = null), "android", streaming = false).stall)
    }

    @Test
    fun `a stall with no countdown holds for the operator`() {
        val held = StallUi.of(golden.missionStall!!.copy(skipInS = null))
        assertEquals("No progress to waypoint 3 for 15 s · Holding for you", held.title(strings))
    }

    @Test
    fun `a blocked return counts down to landing and offers only Land here`() {
        val ret = StallUi.of(golden.missionStall!!.copy(kind = MissionStallKind.Return, seq = null, wpLabel = null, skipInS = 4.2f))
        assertEquals("Return blocked · Landing in 5 s", ret.title(strings))
        assertEquals(listOf(StallAction.Land), ret.actions)
    }

    @Test
    fun `frames within one shown second leave the stall equal`() {
        val a = FlowUi.of(golden.copy(missionStall = golden.missionStall!!.copy(skipInS = 32.9f)), "android", streaming = false)
        val b = FlowUi.of(golden.copy(missionStall = golden.missionStall!!.copy(skipInS = 32.1f)), "android", streaming = false)
        assertEquals(a, b)
    }

    @Test
    fun `each overlay button sends its pilot action`() {
        val sent = mutableListOf<String>()
        show(FlowUi.of(golden, "android", streaming = false).stall!!, sent)
        compose.onNodeWithText("No progress to waypoint 3 for 15 s · Skipping in 33 s").assertExists()
        compose.onNodeWithText(strings.format("flow.mission_stall.skip")).performClick()
        compose.onNodeWithText(strings.format("flow.mission_stall.return")).performClick()
        compose.onNodeWithText(strings.format("flow.mission_stall.land")).performClick()
        assertEquals(listOf("mission_skip", "rth", "land"), sent)
    }

    @Test
    fun `a blocked return shows Land here alone`() {
        val sent = mutableListOf<String>()
        show(StallUi(MissionStallKind.Return, "", 15, 40), sent)
        compose.onNodeWithText("Return blocked · Landing in 40 s").assertExists()
        compose.onAllNodesWithTag(StallAction.Skip.tag).assertCountEquals(0)
        compose.onAllNodesWithTag(StallAction.Return.tag).assertCountEquals(0)
        compose.onNodeWithTag(StallAction.Land.tag).performClick()
        assertEquals(listOf("land"), sent)
    }

    @Test
    fun `the overlay fits the centre's banner height`() {
        show(FlowUi.of(golden, "android", streaming = false).stall!!, mutableListOf())
        val box = compose.onNodeWithTag("flow-mission-stall").getUnclippedBoundsInRoot()
        val h = box.bottom - box.top
        assertTrue("stall overlay $h tall, banner zone $BANNER_HEIGHT", h <= BANNER_HEIGHT)
    }
}
