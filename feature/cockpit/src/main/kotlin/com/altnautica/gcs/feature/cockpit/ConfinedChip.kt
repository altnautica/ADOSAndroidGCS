package com.altnautica.gcs.feature.cockpit

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.skills.hud.ConfinedText
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.feature.cockpit.chrome.ChipTone
import com.altnautica.gcs.feature.cockpit.chrome.ChromeChip

/** Test tag of the top bar's "Confined" chip. */
const val CONFINED_CHIP_TAG = "confined-chip"

/**
 * The top bar's "Confined" chip while the pilot flies the confined profile: the tighter margin
 * and the slow cap near walls are on. Reads only the place class, so other pilot frames do not
 * recompose it.
 */
@Composable
internal fun ConfinedChipSlot(vm: CockpitViewModel) {
    val pilot = vm.pilotState.collectAsStateWithLifecycle()
    val confined = remember(vm) { ConfinedText(SpecText(vm.cockpitSpec.setupStrings)) }
    val text by remember(confined) { derivedStateOf { confined.chip(pilot.value) } }
    text?.let { ConfinedChip(it) }
}

/** The "Confined" chip with [text] (`chip.confined`). */
@Composable
fun ConfinedChip(text: String, modifier: Modifier = Modifier) {
    ChromeChip(Icons.Outlined.UnfoldLess, text, ChipTone.Warn, modifier.testTag(CONFINED_CHIP_TAG))
}
