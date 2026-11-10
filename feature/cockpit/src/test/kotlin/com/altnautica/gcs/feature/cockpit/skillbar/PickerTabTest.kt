package com.altnautica.gcs.feature.cockpit.skillbar

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A picker tab with no skills takes no tap or focus; a populated one does. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PickerTabTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an empty tab is disabled, a populated one is not`() {
        val actions = object : SkillPickerActions {
            override fun onTab(tab: PickerTab) = Unit
            override fun onSelect(skillId: String) = Unit
            override fun onGo(skillId: String) = Unit
            override fun onStop(skillId: String) = Unit
            override fun onDismiss() = Unit
        }
        val ui = SkillPickerUi(PickerTab.Camera, emptyList(), null, setOf(PickerTab.Camera))
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                    SkillPickerSheet(ui, actions) {}
                }
            }
        }
        compose.onNodeWithTag("$PICKER_TAB_TAG:${PickerTab.Navigation.name}").assertIsNotEnabled()
        compose.onNodeWithTag("$PICKER_TAB_TAG:${PickerTab.Camera.name}").assertIsEnabled()
    }
}
