package com.altnautica.gcs.feature.cockpit

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.skills.hud.GoalKindText
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.feature.cockpit.chrome.ChipTone
import com.altnautica.gcs.feature.cockpit.chrome.ChromeChip

/** Test tag of the top bar's goal chip. */
const val GOAL_KIND_CHIP_TAG = "goal-kind-chip"

/**
 * The top bar's goal chip while a skill's goal flies: which goal the skill sent the pilot and,
 * for a follow intent, the viewpoint the pilot picked. Reads only the goal report, so other
 * pilot frames do not recompose it.
 */
@Composable
internal fun GoalKindChipSlot(vm: CockpitViewModel) {
    val pilot = vm.pilotState.collectAsStateWithLifecycle()
    val goal = remember(vm) { GoalKindText(SpecText(vm.cockpitSpec.setupStrings)) }
    val text by remember(goal) { derivedStateOf { goal.chip(pilot.value) } }
    text?.let { GoalKindChip(it) }
}

/** The goal chip with [text] (`pilot.goal_kind.*`). */
@Composable
fun GoalKindChip(text: String, modifier: Modifier = Modifier) {
    ChromeChip(Icons.Outlined.Navigation, text, ChipTone.Neutral, modifier.testTag(GOAL_KIND_CHIP_TAG))
}
