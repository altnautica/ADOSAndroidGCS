package com.altnautica.gcs.core.designsystem.component

import android.view.KeyCharacterMap
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A controller select that ends cancelled (a chord took the button over, or the pad went
 * away) reaches the screen as a centre key-up flagged FLAG_CANCELED. Under the app's guard
 * no control clicks on it, and a hold-to-confirm stops before it fires.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class CancelledConfirmGuardTest {
    @get:Rule
    val compose = createComposeRule()

    private fun centre(action: Int, flags: Int = 0) =
        KeyEvent(NativeKeyEvent(0L, 0L, action, NativeKeyEvent.KEYCODE_DPAD_CENTER, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))

    private val down get() = centre(NativeKeyEvent.ACTION_DOWN)
    private val cancelled get() = centre(NativeKeyEvent.ACTION_UP, NativeKeyEvent.FLAG_CANCELED)
    private val up get() = centre(NativeKeyEvent.ACTION_UP)

    @Test
    fun `a cancelled release clicks neither a button nor a plain clickable`() {
        var button = 0
        var chip = 0
        compose.setContent {
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            AdosTheme {
                CancelledConfirmGuard {
                    Column {
                        AdosButton("Leave", onClick = { button++ }, modifier = Modifier.testTag("button"))
                        Box(Modifier.size(48.dp).testTag("chip").clickable { chip++ })
                    }
                }
            }
        }
        for (tag in listOf("button", "chip")) {
            val node = compose.onNodeWithTag(tag)
            node.requestFocus()
            node.performKeyPress(down)
            node.performKeyPress(cancelled)
        }
        compose.waitForIdle()
        assertEquals(0, button)
        assertEquals(0, chip)
        // A plain press and release on the same control still clicks it, once.
        val leave = compose.onNodeWithTag("button")
        leave.requestFocus()
        leave.performKeyPress(down)
        leave.performKeyPress(up)
        compose.waitForIdle()
        assertEquals(1, button)
    }

    @Test
    fun `a cancelled release ends a hold-to-confirm press`() {
        lateinit var state: HoldToConfirmState
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            state = rememberHoldToConfirmState()
            AdosTheme {
                CancelledConfirmGuard {
                    HoldToConfirmButton("Hold", holdMs = 60_000, onConfirmed = {}, modifier = Modifier.testTag("hold"), state = state)
                }
            }
        }
        val hold = compose.onNodeWithTag("hold")
        hold.requestFocus()
        hold.performKeyPress(down)
        compose.mainClock.advanceTimeBy(100)
        assertTrue("the confirm key holds the control", state.isPressed)
        // The guard drops the cancelled key-up, so the release comes from the guard instead.
        hold.performKeyPress(cancelled)
        compose.mainClock.advanceTimeBy(100)
        assertFalse("a cancelled release must end the hold", state.isPressed)
    }

    @Test
    fun `a cancelled release aborts a keyed slide-to-confirm`() {
        var confirmed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            AdosTheme {
                CancelledConfirmGuard {
                    SlideToConfirm("Start", onConfirmed = { confirmed++ }, modifier = Modifier.testTag("slide"), keyHoldMs = 1500)
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        val slide = compose.onNodeWithTag("slide")
        slide.requestFocus()
        slide.performKeyPress(down)
        compose.mainClock.advanceTimeBy(500)
        slide.performKeyPress(cancelled)
        compose.mainClock.advanceTimeBy(3_000)
        assertEquals("a cancelled release must not complete the slide", 0, confirmed)
        // A full hold with a plain release still confirms.
        slide.performKeyPress(down)
        compose.mainClock.advanceTimeBy(3_000)
        slide.performKeyPress(up)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(1, confirmed)
    }
}
