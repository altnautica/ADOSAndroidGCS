package com.altnautica.gcs.feature.cockpit.skillbar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.ArmRequirement
import com.altnautica.gcs.core.model.Binding
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.ConfirmPolicy
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.model.SkillState
import com.altnautica.gcs.feature.cockpit.chrome.RedrawBeat
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A pending skill's ring turns on the flight screen's beat, not every frame. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PendingRingTest {
    @get:Rule
    val compose = createComposeRule()

    private object Orbit : Skill {
        override val id = "follow:orbit"
        override val category = SkillCategory.Behavior
        override val source = SkillSource.Extension("follow", "Follow me")
        override val labelKey = id
        override val icon = "x"
        override val toggle = true
        override val confirm: ConfirmPolicy? = null
        override val armRequirement = ArmRequirement.Any
        override val cooldownMs = 0L
        override val defaultBinding: Binding? = null
        override fun state(ctx: SkillContext) = SkillState(true, false, null, null, null)
        override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>) = CommandResult.Accepted
    }

    @Test
    fun `the pending chip changes its picture at most once a beat`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                AdosTheme {
                    Box(Modifier.size(240.dp, 64.dp).testTag("chip")) { SkillPickerChip(PickerChip.Pending(Orbit), onOpen = {}) }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        var previous = compose.onNodeWithTag("chip").captureToImage().toPixelMap().buffer.toList()
        var changes = 0
        val startMs = compose.mainClock.currentTime
        repeat(FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            val frame = compose.onNodeWithTag("chip").captureToImage().toPixelMap().buffer.toList()
            if (frame != previous) changes++
            previous = frame
        }
        val elapsedMs = compose.mainClock.currentTime - startMs
        assertTrue("the ring still turns ($changes changes)", changes >= 5)
        assertTrue("$changes changes in $elapsedMs ms", changes <= elapsedMs / RedrawBeat.BEAT_MS + 1)
    }

    private companion object {
        /** About one second of 16 ms frames. */
        const val FRAMES = 60
    }
}
