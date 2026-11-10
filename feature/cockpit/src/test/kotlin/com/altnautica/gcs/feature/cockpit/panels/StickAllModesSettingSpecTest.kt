package com.altnautica.gcs.feature.cockpit.panels

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The quick-settings stick row shows the spec's text. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StickAllModesSettingSpecTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `title comes from the spec`() {
        val strings = testSpec.setupStrings.let { it.copy(strings = it.strings + ("cockpit.qs.stick_all_modes" to "Sticks everywhere")) }
        compose.setContent { CompositionLocalProvider(LocalCockpitStrings provides strings) { StickAllModesSetting(false) {} } }
        compose.onNodeWithText("Sticks everywhere").assertExists()
    }
}
