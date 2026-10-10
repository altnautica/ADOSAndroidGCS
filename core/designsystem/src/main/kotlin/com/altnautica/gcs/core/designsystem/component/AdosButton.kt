package com.altnautica.gcs.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing

enum class AdosButtonVariant { Primary, Secondary, Danger }

@Composable
fun AdosButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AdosButtonVariant = AdosButtonVariant.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val colors = AdosTheme.colors
    val minSize = Modifier.defaultMinSize(minWidth = AdosTheme.touchMin, minHeight = AdosTheme.touchMin)
    val shape = RoundedCornerShape(AdosRadius.Lg)
    val padding = PaddingValues(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2)
    val body: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(AdosSize.IconMd))
            Spacer(Modifier.width(AdosSpacing.S2))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
    when (variant) {
        AdosButtonVariant.Secondary -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.then(minSize),
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            border = BorderStroke(1.dp, colors.borderStrong),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.fgPrimary),
        ) { body() }
        else -> {
            val container = if (variant == AdosButtonVariant.Danger) colors.statusError else colors.accentPrimary
            val content = if (variant == AdosButtonVariant.Danger) colors.statusOn else colors.accentForeground
            Button(
                onClick = onClick,
                modifier = modifier.then(minSize),
                enabled = enabled,
                shape = shape,
                contentPadding = padding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = container,
                    contentColor = content,
                    disabledContainerColor = colors.bgRaised,
                    disabledContentColor = colors.fgTertiary,
                ),
            ) { body() }
        }
    }
}

/** Translucent HUD panel used over video and maps. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(AdosSpacing.S3),
    content: @Composable () -> Unit,
) {
    val colors = AdosTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AdosRadius.Xl),
        color = colors.hudGlass,
        contentColor = colors.hudInk,
        border = BorderStroke(1.dp, colors.hudHair),
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

enum class StatusLevel { Success, Warning, Error, Info, Neutral }

/** Default outlined icon for a status level. */
fun StatusLevel.defaultIcon(): ImageVector = when (this) {
    StatusLevel.Success -> Icons.Outlined.CheckCircle
    StatusLevel.Warning -> Icons.Outlined.WarningAmber
    StatusLevel.Error -> Icons.Outlined.ErrorOutline
    StatusLevel.Info -> Icons.Outlined.Info
    StatusLevel.Neutral -> Icons.Outlined.RadioButtonUnchecked
}

@Composable
private fun StatusLevel.tint(): Color {
    val c = AdosTheme.colors
    return when (this) {
        StatusLevel.Success -> c.statusSuccess
        StatusLevel.Warning -> c.statusWarning
        StatusLevel.Error -> c.statusError
        StatusLevel.Info -> c.statusInfo
        StatusLevel.Neutral -> c.fgSecondary
    }
}

/** Status pill that always pairs an icon with a text label, so state never relies on colour alone. */
@Composable
fun StatusChip(
    level: StatusLevel,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = level.defaultIcon(),
) {
    val tint = level.tint()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AdosRadius.Md),
        color = tint.copy(alpha = 0.14f),
        contentColor = AdosTheme.colors.fgPrimary,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AdosSpacing.S2, vertical = AdosSpacing.S1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(AdosSize.IconSm))
            Spacer(Modifier.width(AdosSpacing.S1))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Preview(widthDp = 1000)
@Composable
private fun AdosButtonPreview() = ThemePreviews {
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        AdosButton("Connect", onClick = {})
        AdosButton("Cancel", onClick = {}, variant = AdosButtonVariant.Secondary)
        AdosButton("Delete", onClick = {}, variant = AdosButtonVariant.Danger, icon = Icons.Outlined.Delete)
        AdosButton("Disabled", onClick = {}, enabled = false)
    }
}

@Preview(widthDp = 1000)
@Composable
private fun GlassSurfacePreview() = ThemePreviews {
    GlassSurface { Text("ALT 120 m", style = MaterialTheme.typography.titleMedium) }
}

@Preview(widthDp = 1000)
@Composable
private fun StatusChipPreview() = ThemePreviews {
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        StatusLevel.entries.forEach { StatusChip(it, it.name) }
    }
}
