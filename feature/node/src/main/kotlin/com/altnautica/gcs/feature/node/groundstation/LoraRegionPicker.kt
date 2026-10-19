package com.altnautica.gcs.feature.node.groundstation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.ProblemState

/** Sentinel for "follow the node's country setting" (`{"region": null}`). */
private const val FOLLOW_COUNTRY = ""

/**
 * Region picker: rows from `GET /api/v1/lora/regions`. Apply asks for a
 * confirm naming old and new region; while a vehicle is armed it is locked
 * (the agent also refuses with `E_ARMED`).
 */
@Composable
internal fun LoraRegionPicker(
    lanes: LanesUiState,
    current: String?,
    followsCountry: Boolean,
    vm: GroundStationViewModel,
    onDismiss: () -> Unit,
) {
    var choice by rememberSaveable { mutableStateOf(if (followsCountry || current == null) FOLLOW_COUNTRY else current) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val write = lanes.regionWrite
    LaunchedEffect(write) {
        if (write is RegionWrite.Done) {
            vm.lanes.dismissRegionWrite()
            onDismiss()
        }
    }
    val chosenCode = choice.ifEmpty { null }
    val changed = chosenCode != (if (followsCountry) null else current)
    AlertDialog(
        onDismissRequest = {
            vm.lanes.dismissRegionWrite()
            onDismiss()
        },
        title = { Text(stringResource(R.string.gs_lora_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                when (val load = lanes.regions) {
                    null, Load.Loading -> LoadingState(stringResource(R.string.node_loading))
                    is Load.Failed -> ProblemState(load.problem, onRetry = vm.lanes::loadRegions)
                    is Load.Ready -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        item {
                            OptionRow(
                                selected = choice == FOLLOW_COUNTRY,
                                title = stringResource(R.string.gs_lora_picker_follow),
                                detail = null,
                                onClick = { choice = FOLLOW_COUNTRY },
                            )
                        }
                        items(load.value, key = { it.code }) { plan ->
                            OptionRow(
                                selected = choice == plan.code,
                                title = listOfNotNull(plan.code, plan.name, plan.planId).joinToString(" · "),
                                detail = planDetail(plan),
                                onClick = { choice = plan.code },
                            )
                        }
                    }
                }
                Text(stringResource(R.string.gs_lora_picker_responsibility), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgSecondary)
                if (lanes.armed) StatusChip(StatusLevel.Warning, stringResource(R.string.gs_lora_region_locked_armed))
                regionWriteText(write)?.let { StatusChip(if (write is RegionWrite.Running) StatusLevel.Info else StatusLevel.Error, it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { confirming = true },
                enabled = changed && !lanes.armed && write != RegionWrite.Running,
            ) { Text(stringResource(R.string.gs_lora_picker_apply)) }
        },
        dismissButton = {
            TextButton(onClick = {
                vm.lanes.dismissRegionWrite()
                onDismiss()
            }) { Text(stringResource(R.string.node_cancel)) }
        },
    )
    if (confirming) {
        val follow = stringResource(R.string.gs_lora_picker_follow)
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.gs_lora_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.gs_lora_confirm_message,
                        if (followsCountry || current == null) follow else current,
                        chosenCode ?: follow,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    vm.lanes.setRegion(chosenCode)
                }, enabled = !lanes.armed) { Text(stringResource(R.string.gs_lora_picker_apply)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.node_cancel)) } },
        )
    }
}

@Composable
private fun OptionRow(selected: Boolean, title: String, detail: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = AdosSpacing.S1),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = AdosSpacing.S2)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgSecondary) }
        }
    }
}

@Composable
private fun planDetail(plan: RegionOptionUi): String {
    val rules = if (plan.rules.isEmpty()) stringResource(R.string.gs_lora_picker_unverified) else plan.rules.map { ruleName(it) }.joinToString(", ")
    val module = stringResource(if (plan.moduleOk) R.string.gs_lora_picker_module_ok else R.string.gs_lora_picker_module_no)
    return listOfNotNull(band(plan.bandMhz), rules, module).joinToString(" · ")
}

@Composable
private fun regionWriteText(write: RegionWrite): String? = when (write) {
    RegionWrite.Idle, is RegionWrite.Done -> null
    RegionWrite.Running -> stringResource(R.string.gs_lora_region_saving)
    is RegionWrite.Failed -> when (write.failure) {
        RegionFailure.Armed -> stringResource(R.string.gs_lora_region_locked_armed)
        RegionFailure.OutOfPlan -> stringResource(R.string.gs_lora_region_out_of_plan)
        RegionFailure.NotAllowed -> stringResource(R.string.gs_lora_region_not_allowed)
        RegionFailure.Other -> write.message ?: stringResource(R.string.node_write_failed)
    }
}
