package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The bottom-left card's dots: a tap on a dot shows that face. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CardDotTapTest {
    @get:Rule
    val compose = createComposeRule()

    private fun assertFace(page: CardPage) {
        for (p in CardPage.entries) {
            val face = compose.onNodeWithTag("face-${p.name}")
            if (p == page) face.assertExists() else face.assertDoesNotExist()
        }
    }

    @Test
    fun `a tap on a card dot switches the face, and the shown face's dot does nothing`() {
        var page by mutableStateOf(CardPage.Map)
        val steps = mutableListOf<Int>()
        compose.setContent {
            Box(Modifier.size(240.dp, 180.dp)) {
                // The view model steps its page the same way (`CardPage.step`).
                SwitchCard(page, page.name, onStep = { delta ->
                    steps += delta
                    page = page.step(delta)
                }) { p ->
                    Box(Modifier.fillMaxSize().testTag("face-${p.name}"))
                }
            }
        }
        assertFace(CardPage.Map)

        compose.onNodeWithTag("card-dot-Obstacles").performClick()
        assertFace(CardPage.Obstacles)

        compose.onNodeWithTag("card-dot-Obstacles").performClick()
        assertFace(CardPage.Obstacles)

        compose.onNodeWithTag("card-dot-Map").performClick()
        assertFace(CardPage.Map)

        compose.onNodeWithTag("card-dot-Attitude").performClick()
        assertFace(CardPage.Attitude)

        assertEquals("one step per tap on another face's dot, none on the shown one", listOf(2, -2, 1), steps)
    }
}
