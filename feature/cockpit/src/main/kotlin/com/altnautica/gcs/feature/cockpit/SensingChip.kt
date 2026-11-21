package com.altnautica.gcs.feature.cockpit

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.skills.hud.SensingText
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.feature.cockpit.chrome.ChipTone
import com.altnautica.gcs.feature.cockpit.chrome.ChromeChip

/** Test tag of the top bar's sensing chip. */
const val SENSING_CHIP_TAG = "sensing-chip"

/**
 * The top bar's sensing chip while the pilot reports its sensing tier: what it sees obstacles
 * with and the speed that still stops inside the range it sees clear. Reads only the sensing
 * report, so other pilot frames do not recompose it.
 */
@Composable
internal fun SensingChipSlot(vm: CockpitViewModel) {
    val pilot = vm.pilotState.collectAsStateWithLifecycle()
    val sensing = remember(vm) { SensingText(SpecText(vm.cockpitSpec.setupStrings)) }
    val text by remember(sensing) { derivedStateOf { sensing.chip(pilot.value) } }
    text?.let { SensingChip(it) }
}

/** The sensing chip with [text] (`pilot.sensing.chip`). */
@Composable
fun SensingChip(text: String, modifier: Modifier = Modifier) {
    ChromeChip(Icons.Outlined.Radar, text, ChipTone.Neutral, modifier.testTag(SENSING_CHIP_TAG))
}
