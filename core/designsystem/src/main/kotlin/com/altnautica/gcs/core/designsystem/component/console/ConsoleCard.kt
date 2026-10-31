package com.altnautica.gcs.core.designsystem.component.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.ThemePreviews
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing

/**
 * Launch card on console surfaces (Home, Quick menu). [emphasis] makes the hero
 * card larger with an accent icon. A disabled card stays focusable and shows
 * [disabledReason] so a controller user can still read why it is unavailable.
 */
@Composable
fun ConsoleCard(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    disabledReason: String? = null,
    emphasis: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val colors = AdosTheme.colors
    val shape = RoundedCornerShape(AdosRadius.Xl)
    val size = if (emphasis) Modifier.size(232.dp, 132.dp) else Modifier.size(168.dp, 104.dp)
    Column(
        modifier = modifier
            .then(size)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .consoleFocus(shape, scaleOnFocus = true)
            .clip(shape)
            .background(colors.bgRaised)
            .semantics { if (!enabled) disabled() }
            .clickable(role = Role.Button) { if (enabled) onClick() }
            .padding(AdosSpacing.S3),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1),
    ) {
        val contentAlpha = if (enabled) 1f else 0.5f
        Icon(
            icon,
            contentDescription = null,
            tint = if (emphasis && enabled) colors.accentPrimary else colors.fgPrimary,
            modifier = Modifier.size(if (emphasis) AdosSize.IconXl else AdosSize.IconLg).alpha(contentAlpha),
        )
        Spacer(Modifier.weight(1f))
        Text(
            title,
            modifier = Modifier.alpha(contentAlpha),
            style = if (emphasis) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            color = colors.fgPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val secondary = if (!enabled && disabledReason != null) disabledReason else subtitle
        if (secondary != null) {
            Text(
                secondary,
                style = MaterialTheme.typography.bodySmall,
                color = if (!enabled) colors.statusWarning else colors.fgSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun ConsoleCardPreview() = ThemePreviews {
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        ConsoleCard("Fly", Icons.Outlined.FlightTakeoff, onClick = {}, subtitle = "Ready to fly", emphasis = true)
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            ConsoleCard("Plan", Icons.Outlined.Map, onClick = {})
            ConsoleCard("Setup", Icons.Outlined.Tune, onClick = {}, enabled = false, disabledReason = "No flight controller")
        }
    }
}
