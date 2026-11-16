package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.cockpit.chrome.ChipTone
import com.altnautica.gcs.feature.cockpit.chrome.ChromeChip
import com.altnautica.gcs.feature.cockpit.chrome.color

/** Test tag of the top bar's "No RC" chip. */
const val NO_RC_CHIP_TAG = "no-rc-chip"

/** Test tag of the follow strip's no-RC line. */
const val NO_RC_STRIP_TAG = "no-rc-strip"

/**
 * The top bar's "No RC" chip beside the "Confined" one while the pilot flies with no RC
 * transmitter. Reads only the declaration, so other pilot frames do not recompose it.
 */
@Composable
internal fun NoRcChipSlot(vm: CockpitViewModel) {
    val pilot = vm.pilotState.collectAsStateWithLifecycle()
    val text by remember(vm) { derivedStateOf { vm.noRc.text.chip(pilot.value) } }
    text?.let { NoRcChip(it) }
}

/** The "No RC" chip with [text] (`chip.no_rc`). */
@Composable
fun NoRcChip(text: String, modifier: Modifier = Modifier) {
    ChromeChip(Icons.Outlined.PhoneAndroid, text, ChipTone.Warn, modifier.testTag(NO_RC_CHIP_TAG))
}

/**
 * The follow strip's no-RC line (`follow.no_rc_strip`): Pause, Land and Return stop the
 * drone, and what a lost link does, after how long. Shown while the pilot declares no RC.
 */
@Composable
internal fun NoRcStrip(vm: CockpitViewModel) {
    val pilot = vm.pilotState.collectAsStateWithLifecycle()
    val line by remember(vm) { derivedStateOf { vm.noRc.text.strip(pilot.value) } }
    line?.let { NoRcStripLine(it) }
}

/** The no-RC line on the HUD glass, warning-toned and on one line. */
@Composable
fun NoRcStripLine(text: String, modifier: Modifier = Modifier) {
    val c = AdosTheme.colors
    Surface(
        modifier = modifier.testTag(NO_RC_STRIP_TAG),
        shape = RoundedCornerShape(8.dp),
        color = c.hudGlassStrong,
        contentColor = c.hudInk,
        border = BorderStroke(1.dp, c.hudHair),
    ) {
        Row(
            Modifier.padding(horizontal = AdosSpacing.S3, vertical = AdosSpacing.S1),
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = ChipTone.Warn.color(), modifier = Modifier.size(14.dp))
            Text(text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
