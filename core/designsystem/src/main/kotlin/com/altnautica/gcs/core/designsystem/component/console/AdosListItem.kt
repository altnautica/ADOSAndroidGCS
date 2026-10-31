package com.altnautica.gcs.core.designsystem.component.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.ThemePreviews
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing

private val SelectedBarWidth = 3.dp

/**
 * List row with a headline, optional supporting line and leading/trailing slots.
 * Focusable and clickable when [onClick] is set; [selected] adds an accent bar at
 * the leading edge. A disabled row stays visible at reduced emphasis.
 */
@Composable
fun AdosListItem(
    headline: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = AdosTheme.colors
    val shape = RoundedCornerShape(AdosRadius.Md)
    val accent = colors.accentPrimary
    val interactive = if (onClick != null) {
        Modifier.consoleFocus(shape).clip(shape).clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AdosTheme.touchMin)
            .then(interactive)
            .semantics { this.selected = selected }
            .background(if (selected) colors.accentPrimary.copy(alpha = 0.08f) else Color.Transparent, shape)
            .drawBehind {
                if (selected) drawRect(accent, size = Size(SelectedBarWidth.toPx(), size.height))
            }
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).padding(start = SelectedBarWidth + AdosSpacing.S3, end = AdosSpacing.S3, top = AdosSpacing.S2, bottom = AdosSpacing.S2),
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(
                    headline,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) colors.accentPrimary else colors.fgPrimary,
                )
                if (supporting != null) {
                    Text(supporting, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                }
            }
            trailing?.invoke()
        }
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun AdosListItemPreview() = ThemePreviews {
    Column {
        AdosListItem("Calibration", onClick = {}, supporting = "Accelerometer, compass, radio", selected = true)
        AdosListItem(
            "Flight modes",
            onClick = {},
            leading = { Icon(Icons.Outlined.Flight, contentDescription = null, tint = AdosTheme.colors.fgSecondary) },
        )
        AdosListItem("Motor test", onClick = {}, enabled = false)
    }
}
