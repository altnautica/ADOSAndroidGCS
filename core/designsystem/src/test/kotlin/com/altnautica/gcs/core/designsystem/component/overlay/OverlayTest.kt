package com.altnautica.gcs.core.designsystem.component.overlay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private val LocalProbe = staticCompositionLocalOf { "host" }

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OverlayTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var focusManager: FocusManager

    @OptIn(ExperimentalComposeUiApi::class)
    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            focusManager = LocalFocusManager.current
            AdosTheme {
                ProvideOverlayHost {
                    Column {
                        AdosButton("Behind", onClick = {})
                        content()
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun overlayContentKeepsTheCallersCompositionLocals() {
        show {
            CompositionLocalProvider(LocalProbe provides "screen") {
                AdosSheet(onDismiss = {}) { Text("probe=${LocalProbe.current}") }
            }
        }
        compose.onNodeWithText("probe=screen").assertExists()
    }

    @Test
    fun sheetTakesInitialFocusAndTrapsIt() {
        show {
            AdosSheet(onDismiss = {}) {
                AdosButton("First", onClick = {})
                AdosButton("Second", onClick = {})
            }
        }
        compose.onNodeWithText("First").assertIsFocused()
        compose.runOnIdle {
            repeat(4) { focusManager.moveFocus(FocusDirection.Up) }
            repeat(4) { focusManager.moveFocus(FocusDirection.Left) }
        }
        compose.onNodeWithText("Behind").assertIsNotFocused()
        compose.runOnIdle { assertTrue(focusManager.moveFocus(FocusDirection.Down)) }
        compose.onNodeWithText("Second").assertIsFocused()
    }

    @Test
    fun backClosesTheNewestOverlay() {
        var dismissed by mutableStateOf(false)
        show {
            if (!dismissed) AdosSheet(onDismiss = { dismissed = true }) { AdosButton("First", onClick = {}) }
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue(dismissed)
        compose.onNodeWithText("First").assertDoesNotExist()
    }

    @Test
    fun dialogFocusesTheFirstConfirmControl() {
        show {
            AdosDialog(
                title = "Title",
                onDismiss = {},
                confirm = { AdosButton("Confirm", onClick = {}) },
                dismiss = { AdosButton("Cancel", onClick = {}) },
            )
        }
        compose.onNodeWithText("Confirm").assertIsFocused()
    }

    @Test
    fun selectRowOpensMenuAndSelects() {
        var picked by mutableStateOf("Metric")
        show {
            SelectSettingRow("Units", listOf("Metric", "Imperial"), picked, optionLabel = { it }, onSelect = { picked = it })
        }
        compose.onNodeWithText("Units").performClick()
        compose.onNodeWithText("Imperial").performClick()
        compose.waitForIdle()
        assertEquals("Imperial", picked)
        compose.onNodeWithText("Metric").assertDoesNotExist()
    }
}
