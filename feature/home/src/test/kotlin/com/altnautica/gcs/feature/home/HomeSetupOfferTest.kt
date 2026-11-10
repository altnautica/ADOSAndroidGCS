package com.altnautica.gcs.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.DeviceId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The aircraft-setup offer on Home: shown for the selected node, each button reaches its callback, absent otherwise. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HomeSetupOfferTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun offerShowsForANodeAndEachButtonCallsBack() {
        var opened = 0
        var later = 0
        compose.setContent {
            AdosTheme { HomeSetupOffer(DeviceId("node-1"), DeviceId("node-1"), onOpen = { opened++ }, onLater = { later++ }) }
        }

        compose.onNodeWithTag("home-setup-offer").assertIsDisplayed()
        compose.onNodeWithTag("home-setup-offer-open").performClick()
        assertEquals(1 to 0, opened to later)
        compose.onNodeWithTag("home-setup-offer-later").performClick()
        assertEquals(1 to 1, opened to later)
    }

    @Test
    fun noOfferWithoutANode() {
        compose.setContent {
            AdosTheme { HomeSetupOffer(null, DeviceId("node-1"), onOpen = {}, onLater = {}) }
        }

        compose.onNodeWithTag("home-setup-offer").assertDoesNotExist()
        compose.onNodeWithTag("home-setup-offer-open").assertDoesNotExist()
        compose.onNodeWithTag("home-setup-offer-later").assertDoesNotExist()
    }

    @Test
    fun noOfferWhileAnotherVehicleIsSelected() {
        var opened = 0
        var selected by mutableStateOf(DeviceId("node-2"))
        compose.setContent {
            AdosTheme { HomeSetupOffer(DeviceId("node-1"), selected, onOpen = { opened++ }, onLater = {}) }
        }

        compose.onNodeWithTag("home-setup-offer").assertDoesNotExist()

        selected = DeviceId("node-1")
        compose.onNodeWithTag("home-setup-offer-open").performClick()
        assertEquals(1, opened)
    }
}
