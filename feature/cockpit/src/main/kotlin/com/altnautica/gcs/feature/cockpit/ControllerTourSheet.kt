package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.overlay.AdosSheet
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.cockpit.flow.specText

private val TOUR_KEYS = listOf("sticks", "safety", "skills")

/** The first-connect controller tour, opened in the cockpit when a pad first connects. */
@Composable
internal fun ControllerTourSheet(step: Int, onNext: () -> Unit, onDismiss: () -> Unit) {
    val key = TOUR_KEYS[step.coerceIn(0, TOUR_KEYS.lastIndex)]
    AdosSheet(onDismiss = onDismiss, title = specText("controls.tour.$key.title")) {
        Column(Modifier.testTag(TAG_COCKPIT_TOUR), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
            Text(specText("controls.tour.$key.body"))
            AdosButton(
                stringResource(if (step >= TOUR_KEYS.lastIndex) R.string.cockpit_tour_done else R.string.cockpit_tour_next),
                onClick = onNext,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

const val TAG_COCKPIT_TOUR = "cockpit_tour"
