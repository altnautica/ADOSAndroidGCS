package com.altnautica.gcs.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId

/**
 * Inline offer to run aircraft setup for [nodeId], a newly found vehicle; nothing shows
 * while it is null. It sits in the Home content above the vehicle row: no scrim, the
 * rest of Home stays usable.
 */
@Composable
internal fun HomeSetupOffer(nodeId: DeviceId?, onOpen: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    if (nodeId == null) return
    val colors = AdosTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2)
            .background(colors.bgSurface, RoundedCornerShape(AdosRadius.Xl))
            .padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S3)
            .testTag("home-setup-offer"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
    ) {
        Icon(Icons.Outlined.Build, contentDescription = null, tint = colors.statusInfo)
        Text(
            stringResource(R.string.home_setup_offer_title),
            style = MaterialTheme.typography.titleSmall,
            color = colors.fgPrimary,
            modifier = Modifier.weight(1f),
        )
        AdosButton(
            stringResource(R.string.home_setup_offer_later),
            onClick = onLater,
            variant = AdosButtonVariant.Secondary,
            modifier = Modifier.testTag("home-setup-offer-later"),
        )
        AdosButton(
            stringResource(R.string.home_setup_offer_open),
            onClick = onOpen,
            modifier = Modifier.testTag("home-setup-offer-open"),
        )
    }
}
