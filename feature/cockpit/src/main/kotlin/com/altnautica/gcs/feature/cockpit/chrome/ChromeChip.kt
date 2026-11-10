package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

enum class ChipTone { Neutral, Good, Warn, Crit, Accent }

@Composable
fun ChipTone.color(): Color {
    val c = LocalChrome.current
    return when (this) {
        ChipTone.Neutral -> c.textDim
        ChipTone.Good -> c.ok
        ChipTone.Warn -> c.warning
        ChipTone.Crit -> c.textCritical
        ChipTone.Accent -> c.text
    }
}

/** A borderless top-bar item: a tinted icon and its label, so state is never carried by colour alone. */
@Composable
fun ChromeChip(icon: ImageVector, text: String, tone: ChipTone, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = LocalChrome.current
    Row(
        modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(c.radiusSmall))
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tone.color(), modifier = Modifier.size(c.icon))
        Text(text, style = c.topBarText, maxLines = 1)
    }
}
