package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlanTitleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `unsaved marker keeps its one-line height when the top bar leaves the title almost no width`() {
        // On a compact portrait phone the vehicle chip and five icon buttons leave the title a sliver;
        // the marker used to wrap one character per line and stretch the whole bar down the map.
        compose.setContent {
            AdosTheme {
                Column {
                    PlanTitle(name = "Untitled plan", dirty = true, modifier = Modifier.width(400.dp).testTag("wide"))
                    PlanTitle(name = "Untitled plan", dirty = true, modifier = Modifier.width(24.dp).testTag("narrow"))
                }
            }
        }
        fun height(tag: String): Dp = compose.onNodeWithTag(tag).getBoundsInRoot().let { it.bottom - it.top }
        assertEquals(height("wide").value, height("narrow").value, 0.5f)
    }
}
