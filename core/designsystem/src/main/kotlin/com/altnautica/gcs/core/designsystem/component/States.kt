package com.altnautica.gcs.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing

/** Action shown under an empty state. */
data class EmptyStateAction(val label: String, val onClick: () -> Unit)

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: EmptyStateAction? = null,
) {
    val colors = AdosTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(AdosSpacing.S6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
    ) {
        Icon(icon, contentDescription = null, tint = colors.fgTertiary, modifier = Modifier.size(AdosSize.IconXl))
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.fgPrimary, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary, textAlign = TextAlign.Center)
        if (action != null) {
            AdosButton(action.label, onClick = action.onClick, variant = AdosButtonVariant.Secondary)
        }
    }
}

@Composable
fun ErrorState(
    reason: String,
    retryLabel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AdosTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(AdosSpacing.S6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.statusError, modifier = Modifier.size(AdosSize.IconXl))
        Text(reason, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary, textAlign = TextAlign.Center)
        AdosButton(retryLabel, onClick = onRetry)
    }
}

@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier) {
    val colors = AdosTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(AdosSpacing.S6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(AdosSize.IconXl),
            color = colors.accentPrimary,
            trackColor = colors.borderDefault,
            strokeWidth = 3.dp,
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AdosSpacing.S10)
            .padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2)
            .semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = AdosTheme.colors.accentPrimary,
    )
}

@Preview(widthDp = 1000)
@Composable
private fun EmptyStatePreview() = ThemePreviews {
    EmptyState(
        icon = Icons.Outlined.FlightTakeoff,
        title = "No vehicles",
        message = "Connect a drone to see it here.",
        action = EmptyStateAction("Add vehicle") {},
    )
}

@Preview(widthDp = 1000)
@Composable
private fun ErrorStatePreview() = ThemePreviews {
    ErrorState("Could not reach the agent.", retryLabel = "Retry", onRetry = {})
}

@Preview(widthDp = 1000)
@Composable
private fun LoadingStatePreview() = ThemePreviews { LoadingState("Loading parameters") }

@Preview(widthDp = 1000)
@Composable
private fun SectionHeaderPreview() = ThemePreviews { SectionHeader("Video") }
