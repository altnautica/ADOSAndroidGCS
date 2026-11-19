package com.altnautica.gcs.feature.cockpit

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.feature.cockpit.chrome.ChipTone
import com.altnautica.gcs.feature.cockpit.chrome.ChromeChip

/** Test tag of the top bar's "SIM ×N" chip. */
const val SIM_CLOCK_CHIP_TAG = "sim-clock-chip"

/**
 * The top bar's "SIM ×N" chip while the pilot follows a simulator's clock, so a run at another
 * speed never reads as real time. Reads only the frame's clock.
 */
@Composable
internal fun SimClockChipSlot(vm: CockpitViewModel) {
    val pilot = vm.pilotState.collectAsStateWithLifecycle()
    val text by remember(vm) { derivedStateOf { pilot.value?.simClock?.label } }
    text?.let { ChromeChip(Icons.Outlined.Speed, it, ChipTone.Warn, Modifier.testTag(SIM_CLOCK_CHIP_TAG)) }
}
