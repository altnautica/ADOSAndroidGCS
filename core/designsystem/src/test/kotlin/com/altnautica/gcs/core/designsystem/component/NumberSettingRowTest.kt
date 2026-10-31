package com.altnautica.gcs.core.designsystem.component

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.component.overlay.ProvideOverlayHost
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class NumberSettingRowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dpadStepsTheFocusedRowAndStopsAtTheBound() {
        var value by mutableIntStateOf(8)
        lateinit var focusManager: FocusManager
        compose.setContent {
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            focusManager = LocalFocusManager.current
            AdosTheme {
                ProvideOverlayHost {
                    NumberSettingRow(
                        "Altitude",
                        value = value,
                        onValueChange = { value = it },
                        range = 0..10,
                        step = 2,
                        decrementDescription = "Less",
                        incrementDescription = "More",
                    )
                }
            }
        }
        compose.runOnIdle { focusManager.moveFocus(FocusDirection.Next) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(10, value) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(10, value) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.runOnIdle { assertEquals(8, value) }
    }
}
