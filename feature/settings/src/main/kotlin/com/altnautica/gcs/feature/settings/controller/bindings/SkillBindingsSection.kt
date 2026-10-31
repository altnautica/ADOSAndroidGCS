package com.altnautica.gcs.feature.settings.controller.bindings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.console.ControllerGlyph
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.skills.binding.LoadoutSlot
import com.altnautica.gcs.core.skills.builtinLabelRes
import com.altnautica.gcs.feature.settings.R
import com.altnautica.gcs.feature.settings.controller.consoleButtonFor

/** Actions of the bindings editor. */
internal class BindingsActions(
    val onAssign: (Int, String?) -> Unit,
    val onCaptureKey: (Int) -> Unit,
    val onCaptureButton: (Int) -> Unit,
    val onClearKey: (Int) -> Unit,
    val onClearButton: (Int) -> Unit,
    val onCancelCapture: () -> Unit,
    val onReset: () -> Unit,
)

internal fun SkillBindingsViewModel.actions() = BindingsActions(
    onAssign = ::assign,
    onCaptureKey = { startCapture(it, CaptureKind.Key) },
    onCaptureButton = { startCapture(it, CaptureKind.Button) },
    onClearKey = ::clearKey,
    onClearButton = ::clearButton,
    onCancelCapture = ::cancelCapture,
    onReset = ::reset,
)

/** Skill Bar slots with their keyboard chords and gamepad buttons, as list items. */
internal fun LazyListScope.skillBindingsItems(state: BindingsUiState, actions: BindingsActions) {
    item(key = "bindings-header") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(stringResource(R.string.settings_bindings_title), Modifier.weight(1f))
            AdosButton(
                label = stringResource(R.string.settings_bindings_reset),
                onClick = actions.onReset,
                variant = AdosButtonVariant.Secondary,
                modifier = Modifier.padding(end = AdosSpacing.S4),
            )
        }
    }
    item(key = "bindings-hint") {
        Text(
            stringResource(R.string.settings_bindings_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = AdosTheme.colors.fgSecondary,
            modifier = Modifier.padding(horizontal = AdosSpacing.S4),
        )
    }
    when (state) {
        BindingsUiState.Loading -> item(key = "bindings-loading") { LoadingState(stringResource(R.string.settings_bindings_loading)) }
        is BindingsUiState.Ready -> items(state.loadout.slots, key = { "slot-${it.index}" }) { slot ->
            SlotRow(
                slot = slot,
                skills = state.skills,
                capturing = state.capture?.takeIf { it.slotIndex == slot.index }?.kind,
                actions = actions,
            )
        }
    }
}

@Composable
private fun skillLabel(skill: Skill): String = skill.builtinLabelRes()?.let { stringResource(it) } ?: skill.labelKey

@Composable
private fun SlotRow(slot: LoadoutSlot, skills: List<Skill>, capturing: CaptureKind?, actions: BindingsActions) {
    val empty = stringResource(R.string.settings_bindings_empty)
    val labels = skills.associate { it.id to skillLabel(it) }
    val options = listOf<String?>(null) + skills.map { it.id }
    val index = slot.index
    Column(Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S2)) {
        SelectSettingRow(
            title = stringResource(R.string.settings_bindings_slot, index + 1),
            options = options,
            selected = slot.skillId,
            optionLabel = { id -> id?.let { labels[it] ?: it } ?: empty },
            onSelect = { actions.onAssign(index, it) },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4),
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BindingControl(
                value = slot.keyChord?.uppercase(),
                capturing = capturing == CaptureKind.Key,
                captureLabel = stringResource(R.string.settings_bindings_press_key),
                setLabel = stringResource(R.string.settings_bindings_set_key),
                onCapture = { actions.onCaptureKey(index) },
                onClear = { actions.onClearKey(index) },
                onCancel = actions.onCancelCapture,
                modifier = Modifier.weight(1f),
            )
            BindingControl(
                value = null,
                capturing = capturing == CaptureKind.Button,
                captureLabel = stringResource(R.string.settings_bindings_press_button),
                setLabel = stringResource(R.string.settings_bindings_set_button),
                onCapture = { actions.onCaptureButton(index) },
                onClear = { actions.onClearButton(index) },
                onCancel = actions.onCancelCapture,
                modifier = Modifier.weight(1f),
                bound = slot.gamepadButton,
            )
        }
    }
}

/**
 * A capture button showing the bound chord ([value]) or gamepad button ([bound], drawn
 * as its controller glyph), with a clear action once something is bound.
 */
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
    bound: Int? = null,
) {
    val glyph = bound?.let(::consoleButtonFor)
    val hasValue = value != null || bound != null
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        if (glyph != null && !capturing) ControllerGlyph(glyph)
        AdosButton(
            label = when {
                capturing -> captureLabel
                value != null -> value
                bound != null && glyph == null -> stringResource(R.string.settings_bindings_button_index, bound)
                bound != null -> stringResource(R.string.settings_bindings_change_button)
                else -> setLabel
            },
            onClick = if (capturing) onCancel else onCapture,
            variant = if (capturing) AdosButtonVariant.Primary else AdosButtonVariant.Secondary,
            modifier = Modifier.weight(1f),
        )
        if (hasValue && !capturing) {
            IconButton(onClick = onClear) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.settings_bindings_clear))
            }
        }
    }
}
