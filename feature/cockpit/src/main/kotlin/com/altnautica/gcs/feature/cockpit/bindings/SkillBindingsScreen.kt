package com.altnautica.gcs.feature.cockpit.bindings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.skills.binding.LoadoutSlot
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.skillbar.gamepadButtonName
import com.altnautica.gcs.feature.cockpit.skillbar.skillLabel

/** Skill Bar slots with their keyboard chords and gamepad buttons; hosted by the settings route. */
@Composable
fun SkillBindingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: SkillBindingsViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(vm) {
        vm.messages.collect { m ->
            snackbar.showSnackbar(
                resources.getString(if (m == BindMessage.Reserved) R.string.cockpit_bindings_reserved else R.string.cockpit_bindings_out_of_range),
            )
        }
    }
    SkillBindingsContent(
        state = state,
        onBack = onBack,
        onAssign = vm::assign,
        onCaptureKey = { vm.startCapture(it, CaptureKind.Key) },
        onCaptureButton = { vm.startCapture(it, CaptureKind.Button) },
        onClearKey = vm::clearKey,
        onClearButton = vm::clearButton,
        onCancelCapture = vm::cancelCapture,
        onReset = vm::reset,
        snackbar = { SnackbarHost(snackbar) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillBindingsContent(
    state: BindingsUiState,
    onBack: () -> Unit,
    onAssign: (Int, String?) -> Unit,
    onCaptureKey: (Int) -> Unit,
    onCaptureButton: (Int) -> Unit,
    onClearKey: (Int) -> Unit,
    onClearButton: (Int) -> Unit,
    onCancelCapture: () -> Unit,
    onReset: () -> Unit,
    snackbar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = AdosTheme.colors.bgCanvas,
        snackbarHost = snackbar,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cockpit_bindings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cockpit_back))
                    }
                },
                actions = { TextButton(onClick = onReset) { Text(stringResource(R.string.cockpit_bindings_reset)) } },
            )
        },
    ) { padding ->
        when (state) {
            BindingsUiState.Loading -> LoadingState(stringResource(R.string.cockpit_bindings_loading), Modifier.padding(padding))
            is BindingsUiState.Ready -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
            ) {
                item {
                    Text(
                        stringResource(R.string.cockpit_bindings_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AdosTheme.colors.fgSecondary,
                        modifier = Modifier.padding(horizontal = AdosSpacing.S4),
                    )
                }
                items(state.loadout.slots, key = { it.index }) { slot ->
                    SlotRow(
                        slot = slot,
                        skills = state.skills,
                        capturing = state.capture?.takeIf { it.slotIndex == slot.index }?.kind,
                        onAssign = { onAssign(slot.index, it) },
                        onCaptureKey = { onCaptureKey(slot.index) },
                        onCaptureButton = { onCaptureButton(slot.index) },
                        onClearKey = { onClearKey(slot.index) },
                        onClearButton = { onClearButton(slot.index) },
                        onCancelCapture = onCancelCapture,
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotRow(
    slot: LoadoutSlot,
    skills: List<Skill>,
    capturing: CaptureKind?,
    onAssign: (String?) -> Unit,
    onCaptureKey: () -> Unit,
    onCaptureButton: () -> Unit,
    onClearKey: () -> Unit,
    onClearButton: () -> Unit,
    onCancelCapture: () -> Unit,
) {
    val empty = stringResource(R.string.cockpit_bindings_empty)
    val labels = skills.associate { it.id to skillLabel(it) }
    val options = listOf<String?>(null) + skills.map { it.id }
    Column(Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S2)) {
        SelectSettingRow(
            title = stringResource(R.string.cockpit_bindings_slot, slot.index + 1),
            options = options,
            selected = slot.skillId,
            optionLabel = { id -> id?.let { labels[it] ?: it } ?: empty },
            onSelect = onAssign,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4),
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BindingControl(
                value = slot.keyChord?.uppercase(),
                capturing = capturing == CaptureKind.Key,
                captureLabel = stringResource(R.string.cockpit_bindings_press_key),
                setLabel = stringResource(R.string.cockpit_bindings_set_key),
                onCapture = onCaptureKey,
                onClear = onClearKey,
                onCancel = onCancelCapture,
                modifier = Modifier.weight(1f),
            )
            BindingControl(
                value = slot.gamepadButton?.let(::gamepadButtonName),
                capturing = capturing == CaptureKind.Button,
                captureLabel = stringResource(R.string.cockpit_bindings_press_button),
                setLabel = stringResource(R.string.cockpit_bindings_set_button),
                onCapture = onCaptureButton,
                onClear = onClearButton,
                onCancel = onCancelCapture,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BindingControl(
    value: String?,
    capturing: Boolean,
    captureLabel: String,
    setLabel: String,
    onCapture: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AdosButton(
            label = when {
                capturing -> captureLabel
                value != null -> value
                else -> setLabel
            },
            onClick = if (capturing) onCancel else onCapture,
            variant = if (capturing) AdosButtonVariant.Primary else AdosButtonVariant.Secondary,
            modifier = Modifier.weight(1f),
        )
        if (value != null && !capturing) {
            IconButton(onClick = onClear) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cockpit_bindings_clear))
            }
        }
    }
}
