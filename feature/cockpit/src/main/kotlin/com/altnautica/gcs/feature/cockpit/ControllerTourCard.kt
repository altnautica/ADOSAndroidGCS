package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.cockpit.chrome.ChromePanel
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome
import com.altnautica.gcs.feature.cockpit.flow.specText

private val TOUR_KEYS = listOf("sticks", "safety", "skills")

/**
 * The first-connect controller tour: a small card over the video, never a sheet or a
 * scrim, so STOP, the left column and the rest of the cockpit stay visible and usable. It
 * is an app-navigation overlay: the pad's D-pad and Cross drive Next and Close, Back closes it.
 */
@Composable
internal fun ControllerTourCard(step: Int, onNext: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalChrome.current
    val key = TOUR_KEYS[step.coerceIn(0, TOUR_KEYS.lastIndex)]
    val focus = remember { FocusRequester() }
    LaunchedEffect(step) { runCatching { focus.requestFocus() } }
    // Back closes the card; without this it would fall through to the route and leave the cockpit.
    if (LocalNavigationEventDispatcherOwner.current != null) {
        val close by rememberUpdatedState(onClose)
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = true,
            onBackCompleted = { close() },
        )
    }
    ChromePanel(modifier.widthIn(max = 360.dp).testTag(TAG_COCKPIT_TOUR)) {
        Column(Modifier.padding(AdosSpacing.S3), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            Text(specText("controls.tour.$key.title"), style = c.valueText.copy(color = c.text))
            Text(specText("controls.tour.$key.body"), style = c.labelText.copy(color = c.text))
            Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                AdosButton(
                    specText(if (step >= TOUR_KEYS.lastIndex) "controls.tour.done" else "controls.tour.next"),
                    onClick = onNext,
                    modifier = Modifier.focusRequester(focus),
                )
                AdosButton(specText("cockpit.close"), onClick = onClose, variant = AdosButtonVariant.Secondary)
            }
        }
    }
}

const val TAG_COCKPIT_TOUR = "cockpit_tour"
