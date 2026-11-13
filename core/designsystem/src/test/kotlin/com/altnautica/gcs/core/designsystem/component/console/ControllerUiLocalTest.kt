package com.altnautica.gcs.core.designsystem.component.console

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private var bystanderCompositions = 0

/** Reads nothing from the controller presentation. */
@Composable
private fun Bystander() {
    SideEffect { bystanderCompositions++ }
    Text("bystander")
}

@Composable
private fun Reader() {
    Text(if (LocalControllerUi.current.active) "controller" else "touch")
}

/** A switch between touch and controller recomposes only what reads the controller presentation. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ControllerUiLocalTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an input mode switch leaves composables that do not read it alone`() {
        var active by mutableStateOf(false)
        bystanderCompositions = 0
        compose.setContent {
            CompositionLocalProvider(LocalControllerUi provides ControllerUi(active = active)) {
                Column {
                    Bystander()
                    Reader()
                }
            }
        }
        compose.onNodeWithText("touch").assertExists()
        val before = bystanderCompositions

        active = true
        compose.waitForIdle()
        compose.onNodeWithText("controller").assertExists()
        active = false
        compose.waitForIdle()
        compose.onNodeWithText("touch").assertExists()

        assertEquals(before, bystanderCompositions)
    }
}
