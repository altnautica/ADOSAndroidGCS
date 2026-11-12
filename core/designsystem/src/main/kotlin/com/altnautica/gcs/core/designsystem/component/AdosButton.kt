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
import com.altnautica.gcs.core.designsystem.component.console.consoleFocus
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.designsystem.tokens.AdosColorRoles
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

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
            modifier = modifier.consoleFocus(shape).then(minSize),
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
                modifier = modifier.consoleFocus(shape).then(minSize),
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

internal fun StatusLevel.tint(c: AdosColorRoles): Color = when (this) {
    StatusLevel.Success -> c.statusSuccess
    StatusLevel.Warning -> c.statusWarning
    StatusLevel.Error -> c.statusError
    StatusLevel.Info -> c.statusInfo
    StatusLevel.Neutral -> c.fgSecondary
}

/** Colours of an outlined status chip: no fill, a hairline in the state hue, text in a readable variant of it. */
data class StatusChipColors(val container: Color, val border: Color, val icon: Color, val text: Color)

/** Minimum contrast for chip text (WCAG AA for small text). */
const val STATUS_CHIP_TEXT_MIN_CONTRAST = 4.5f

fun statusChipColors(level: StatusLevel, roles: AdosColorRoles): StatusChipColors {
    val tint = level.tint(roles)
    return StatusChipColors(
        container = Color.Transparent,
        border = tint.copy(alpha = 0.4f),
        icon = tint,
        text = readableOn(tint, listOf(roles.bgCanvas, roles.bgSurface, roles.bgRaised), roles.fgPrimary),
    )
}

/**
 * A state hue used as small text keeps its hue only while it reads at 4.5:1 on every background
 * it can sit on; below that it is blended toward the theme's primary text colour until it does.
 */
internal fun readableOn(hue: Color, backgrounds: List<Color>, fallback: Color): Color {
    var step = 0
    var candidate = hue
    while (step < 10 && backgrounds.any { contrastRatio(candidate, it) < STATUS_CHIP_TEXT_MIN_CONTRAST }) {
        step++
        candidate = lerp(hue, fallback, step / 10f)
    }
    return candidate
}

fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** Status chip that always pairs an icon with a text label, so state never relies on colour alone. */
@Composable
fun StatusChip(
    level: StatusLevel,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = level.defaultIcon(),
) {
    val colors = statusChipColors(level, AdosTheme.colors)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AdosRadius.Sm),
        color = colors.container,
        contentColor = colors.text,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AdosSpacing.S2, vertical = AdosSpacing.S1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = colors.icon, modifier = Modifier.size(AdosSize.IconSm))
            Spacer(Modifier.width(AdosSpacing.S1))
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.text)
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
