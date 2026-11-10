package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.overlay.AdosConfirmSheet
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.cockpit.flow.specText

/**
 * Asks before leaving the cockpit while the vehicle is armed. Like every flight-screen
 * sheet it starts below the top bar (the caller provides the measured inset), so STOP
 * takes the first press with it open.
 */
@Composable
internal fun LeaveArmedSheet(onStay: () -> Unit, onLeave: () -> Unit) {
    AdosConfirmSheet(
        title = specText("cockpit.leave_armed.title"),
        message = specText("cockpit.leave_armed.message"),
        onDismiss = onStay,
        danger = true,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            AdosButton(specText("cockpit.stay"), onClick = onStay, modifier = Modifier.weight(1f), variant = AdosButtonVariant.Secondary)
            AdosButton(specText("cockpit.leave"), onClick = onLeave, modifier = Modifier.weight(1f), variant = AdosButtonVariant.Danger)
        }
    }
}
