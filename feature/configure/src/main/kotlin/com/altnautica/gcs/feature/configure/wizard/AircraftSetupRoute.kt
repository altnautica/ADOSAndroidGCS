package com.altnautica.gcs.feature.configure.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.altnautica.gcs.core.agent.fcsetup.FcSetupApi
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.agent.fcsetup.SetupWizard
import com.altnautica.gcs.core.agent.fcsetup.SetupWizardState
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.console.ConsoleScaffold
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.configure.R
import kotlinx.serialization.json.JsonObject

/**
 * Settings › Aircraft setup for [nodeId]: the `ground` and `air` wizards with each
 * step runnable on its own, the setup report and the parameter snapshots. [wizard]
 * (and [step]) open straight into one wizard or step; [run] runs that step on opening.
 */
@Composable
fun AircraftSetupRoute(nodeId: DeviceId, onBack: () -> Unit, wizard: String? = null, step: String? = null, run: Boolean = false) {
    val vm = hiltViewModel<WizardViewModel, WizardViewModel.Factory>(
        key = "aircraft-setup-${nodeId.value}-$wizard-$step-$run",
        creationCallback = { it.create(nodeId.value, wizard, step, run) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalSetupText provides vm.text) {
        AircraftSetupScreen(
            state = state,
            onBack = onBack,
            onOpen = vm::open,
            onStart = vm::start,
            onResume = vm::resume,
            onRunStep = vm::runStep,
            onAction = vm::action,
            onAbort = vm::abort,
            onRevert = vm::revert,
            onList = vm::back,
        )
    }
}

@Composable
fun AircraftSetupScreen(
    state: WizardUiState,
    onBack: () -> Unit,
    onOpen: (wizard: String, step: String?) -> Unit,
    onStart: (String) -> Unit,
    onResume: (String) -> Unit,
    onRunStep: (wizard: String, step: String) -> Unit,
    onAction: (step: com.altnautica.gcs.core.agent.fcsetup.SetupStep, action: String, args: JsonObject?) -> Unit,
    onAbort: () -> Unit,
    onRevert: (String) -> Unit,
    onList: () -> Unit,
) {
    if (state.focus != null) {
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = true,
            onBackCompleted = onList,
        )
    }
    val text = LocalSetupText.current
    ConsoleScaffold(title = text.format(KEY_ENTRY_SETTINGS, emptyMap()), onBack = if (state.focus != null) onList else onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.agent == null -> LoadingState(stringResource(R.string.cfg_surface_loading), Modifier.align(Alignment.Center))
                state.agent == false -> EmptyState(
                    icon = Icons.Outlined.Cable,
                    title = stringResource(R.string.cfg_setup_direct_title),
                    message = stringResource(R.string.cfg_setup_direct_message),
                )
                state.setup.overview == null && state.setup.problem != null -> SetupProblemText(state.setup.problem, Modifier.align(Alignment.Center))
                state.setup.overview == null -> LoadingState(stringResource(R.string.cfg_surface_loading), Modifier.align(Alignment.Center))
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AdosSpacing.S4).widthIn(max = 720.dp),
                    verticalArrangement = Arrangement.spacedBy(AdosSpacing.S4),
                ) {
                    state.setup.problem?.let { SetupProblemText(it) }
                    val actions = SetupActions(onOpen, onStart, onResume, onRunStep, onAction, onAbort, onList)
                    if (state.focus == null) {
                        WizardList(state, actions, onRevert)
                    } else {
                        WizardPane(state, actions, onRevert)
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardPane(
    state: WizardUiState,
    actions: SetupActions,
    onRevert: (String) -> Unit,
) {
    val text = LocalSetupText.current
    val focus = state.focus ?: return
    val wizard = state.wizard
    val step = state.step
    when {
        state.showResults -> {
            Text(wizardTitle(focus.wizard), style = MaterialTheme.typography.headlineSmall, color = AdosTheme.colors.fgPrimary)
            val failed = failedSteps(wizard)
            if (failed > 0) {
                Text(pluralStringResource(R.plurals.cfg_setup_wizard_steps_failed, failed, failed), color = AdosTheme.colors.statusWarning)
            } else {
                Text(stringResource(R.string.cfg_setup_wizard_done), color = AdosTheme.colors.statusSuccess)
            }
            wizard?.let(SetupOverview::filedSteps)?.forEach { s ->
                SetupOverview.resultRows(s.result).takeIf { it.isNotEmpty() }?.let {
                    Text(text.format(s.titleKey, s.args.textArgs()), style = MaterialTheme.typography.titleSmall, color = AdosTheme.colors.fgPrimary)
                    ResultCard(it)
                }
            }
            Snapshots(state, onRevert)
        }
        step != null -> {
            // One step at a time: where it sits in the walk, the step, then the way back.
            val guided = wizard?.let(SetupOverview::guidedSteps).orEmpty()
            val k = guided.indexOfFirst { it.step == step.step }
            if (k >= 0) {
                Text(
                    text.format("setup.overview.step_of", mapOf("k" to (k + 1).toString(), "n" to guided.size.toString())),
                    style = MaterialTheme.typography.labelLarge,
                    color = AdosTheme.colors.fgSecondary,
                )
                LinearProgressIndicator(progress = { (k + 1f) / guided.size }, modifier = Modifier.fillMaxWidth())
            }
            WizardStepView(step, busy = state.setup.actionsBlocked, onAction = { a, args -> actions.onAction(step, a, args) })
            Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                AdosButton(
                    text.format("setup.overview.back", emptyMap()),
                    onClick = actions.onList,
                    variant = AdosButtonVariant.Secondary,
                    modifier = Modifier.testTag("setup-back-overview"),
                )
                val live = state.setup.overview?.running?.wizard == focus.wizard
                if (live || wizard?.state == SetupWizardState.Running || step.state == SetupStepState.Running) {
                    AbortControl(focus.wizard, actions.onAbort)
                }
            }
        }
        wizard != null -> WizardOverview(state, wizard, actions)
    }
}

/** What the overview and the guided step can do. */
class SetupActions(
    val onOpen: (wizard: String, step: String?) -> Unit,
    val onStart: (String) -> Unit,
    val onResume: (String) -> Unit,
    val onRunStep: (wizard: String, step: String) -> Unit,
    val onAction: (step: com.altnautica.gcs.core.agent.fcsetup.SetupStep, action: String, args: JsonObject?) -> Unit,
    val onAbort: () -> Unit,
    val onList: () -> Unit,
)

@Composable
private fun WizardList(state: WizardUiState, actions: SetupActions, onRevert: (String) -> Unit) {
    val overview = state.setup.overview ?: return
    val vehicle = listOfNotNull(
        listOfNotNull(SetupOverview.firmwareLabel(overview.firmware), overview.firmwareVersion).joinToString(" ").ifEmpty { null },
        overview.vehicleClass,
    ).joinToString(" · ")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        if (vehicle.isNotEmpty()) Text(vehicle, style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgSecondary)
        overview.uid?.let { uid ->
            var open by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp).testTag("setup-details")) {
                Text(stringResource(R.string.cfg_setup_details) + if (open) " ▾" else " ▸")
            }
            if (open) {
                SelectionContainer {
                    Text(uid, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = AdosTheme.colors.fgPrimary)
                }
            }
        }
    }
    listOf(FcSetupApi.WIZARD_GROUND, FcSetupApi.WIZARD_AIR).forEach { id ->
        state.setup.wizard(id)?.let { WizardOverview(state, it, actions) }
    }
    Snapshots(state, onRevert)
}

/**
 * One wizard's overview: its title and state, "N of M done" with a bar, the interrupted
 * banner, the blockers (instead of the button), the primary button (Continue setup, Start
 * setup, Run again) and a grid of component cards. A card that needs attention has a red
 * border; tapping a card opens its step: the step itself while it is the running one, else it
 * runs on its own once nothing else runs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WizardOverview(state: WizardUiState, wizard: SetupWizard, actions: SetupActions) {
    val text = LocalSetupText.current
    val colors = AdosTheme.colors
    val overview = state.setup.overview ?: return
    val id = wizard.id
    SectionHeader(wizardTitle(id))
    val running = SetupOverview.runningStep(overview, wizard)
    val failed = failedSteps(wizard)
    Text(
        when {
            failed > 0 -> pluralStringResource(R.plurals.cfg_setup_wizard_steps_failed, failed, failed)
            running != null -> wizardStateLabel(SetupWizardState.Running)
            else -> wizardStateLabel(wizard.state)
        },
        color = when {
            failed > 0 -> colors.statusWarning
            wizardSucceeded(wizard) && running == null -> colors.statusSuccess
            else -> colors.fgSecondary
        },
    )
    val (done, total) = SetupOverview.progress(wizard)
    if (total > 0) {
        Text(text.format("setup.overview.progress", mapOf("done" to done.toString(), "total" to total.toString())), color = colors.fgPrimary)
        LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().testTag("setup-progress-$id"))
    }
    if (wizard.state == SetupWizardState.Interrupted && running == null) {
        Text(
            text.format("setup.overview.interrupted", emptyMap()),
            color = colors.fgPrimary,
            modifier = Modifier.fillMaxWidth().background(colors.bgRaised, RoundedCornerShape(AdosRadius.Lg)).padding(AdosSpacing.S3)
                .testTag("setup-interrupted-$id"),
        )
    }
    if (wizard.blockers.isNotEmpty()) {
        Text(text.format("setup.overview.blocked", emptyMap()), color = colors.statusError, style = MaterialTheme.typography.titleSmall)
        wizard.blockers.forEach { Text(text.format(it, emptyMap()), color = colors.statusError) }
    }
    // A run in progress always has its way in, whatever blocks a new one.
    if (wizard.blockers.isEmpty() || running != null) {
        val primary = SetupOverview.primary(wizard.state, running != null)
        val enabled = wizard.available && !state.setup.actionsBlocked
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            if (primary == PrimaryKind.RunAgain) {
                // Run again starts over and clears the records: it asks for a hold.
                HoldToConfirmButton(
                    label = stringResource(R.string.cfg_setup_hold_to, text.format(primary.labelKey, emptyMap())),
                    holdMs = SETUP_HOLD_MS,
                    onConfirmed = { actions.onStart(id) },
                    enabled = enabled,
                    modifier = Modifier.testTag("setup-primary-$id"),
                )
            } else {
                AdosButton(
                    text.format(primary.labelKey, emptyMap()),
                    onClick = {
                        when (primary) {
                            PrimaryKind.Resume -> actions.onResume(id)
                            PrimaryKind.Continue -> actions.onOpen(id, running)
                            PrimaryKind.Start, PrimaryKind.RunAgain -> actions.onStart(id)
                        }
                    },
                    enabled = enabled,
                    modifier = Modifier.testTag("setup-primary-$id"),
                )
            }
            if (wizard.completed && running == null) {
                AdosButton(stringResource(R.string.cfg_setup_view_results), onClick = { actions.onOpen(id, null) }, variant = AdosButtonVariant.Secondary)
            }
        }
    }
    // Equal columns of at least 160 dp, so the cards line up on a phone and a tablet.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = AdosSpacing.S2
        val columns = ((maxWidth + gap) / (CARD_MIN + gap)).toInt().coerceIn(1, MAX_CARD_COLUMNS)
        val cardWidth = (maxWidth - gap * (columns - 1)) / columns
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            SetupOverview.cards(wizard, state.flags).forEach { card ->
                val target = SetupOverview.target(card, state.flags)
                SetupCardTile(
                    card,
                    enabled = wizard.available && !state.setup.actionsBlocked && (overview.running == null || running == target.step),
                    onClick = {
                        if (running == target.step || wizard.state == SetupWizardState.Running) actions.onOpen(id, target.step) else actions.onRunStep(id, target.step)
                    },
                    modifier = Modifier.width(cardWidth),
                )
            }
        }
    }
}

/**
 * Abort for the open run. Aborting Aircraft setup asks for a hold; Flight tuning's stays one
 * tap, because in the air it lands the aircraft.
 */
@Composable
private fun AbortControl(wizard: String, onAbort: () -> Unit) {
    val label = LocalSetupText.current.format(KEY_ACTION_ABORT, emptyMap())
    if (wizard == FcSetupApi.WIZARD_AIR) {
        AdosButton(label, onClick = onAbort, variant = AdosButtonVariant.Danger, modifier = Modifier.testTag("setup-abort"))
    } else {
        HoldToConfirmButton(
            label = stringResource(R.string.cfg_setup_hold_to, label),
            holdMs = SETUP_HOLD_MS,
            onConfirmed = onAbort,
            danger = true,
            modifier = Modifier.testTag("setup-abort"),
        )
    }
}

private val CARD_MIN = 160.dp
private const val MAX_CARD_COLUMNS = 4

/** One component card: title, the summary of its current values, and its state badge. */
@Composable
private fun SetupCardTile(card: SetupCard, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val text = LocalSetupText.current
    val colors = AdosTheme.colors
    val attention = card.state == CardState.Attention
    val shape = RoundedCornerShape(AdosRadius.Lg)
    Column(
        modifier
            .heightIn(min = 96.dp)
            .clip(shape)
            .border(if (attention) 2.dp else 1.dp, if (attention) colors.statusError else colors.borderDefault, shape)
            .background(colors.bgSurface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(AdosSpacing.S3)
            .testTag("setup-card-${card.id}"),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1),
    ) {
        Text(text.format(card.titleKey, emptyMap()), style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
        SetupOverview.summary(card, text).takeIf { it.isNotEmpty() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (card.flag != null) colors.statusError else colors.fgSecondary,
                maxLines = 3,
                modifier = Modifier.testTag("setup-card-${card.id}-summary"),
            )
        }
        Text(
            text.format("setup.card.state.${card.state.key}", emptyMap()),
            style = MaterialTheme.typography.labelMedium,
            color = when (card.state) {
                CardState.Done, CardState.NotNeeded -> colors.statusSuccess
                CardState.Attention -> colors.statusError
                CardState.InProgress -> colors.statusWarning
                CardState.NotSet, CardState.Optional, CardState.AfterSetup -> colors.fgSecondary
            },
        )
    }
}

@Composable
private fun Snapshots(state: WizardUiState, onRevert: (String) -> Unit) {
    val snapshots = state.setup.overview?.report?.snapshots.orEmpty()
    if (snapshots.isEmpty()) return
    SectionHeader(stringResource(R.string.cfg_setup_snapshots))
    snapshots.forEach { snap ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(SetupOverview.snapshotTime(snap.ts ?: snap.file) ?: snap.ts ?: snap.file, Modifier.weight(1f), color = AdosTheme.colors.fgPrimary)
            HoldToConfirmButton(
                label = LocalSetupText.current.format(KEY_ACTION_REVERT, emptyMap()),
                holdMs = SETUP_HOLD_MS,
                onConfirmed = { onRevert(snap.file) },
                enabled = !state.setup.actionsBlocked,
                danger = true,
            )
        }
    }
}

@Composable
private fun wizardTitle(id: String): String = LocalSetupText.current.format("setup.wizard.$id.title", emptyMap())

const val KEY_ENTRY_SETTINGS = "setup.wizard.entry.settings"
const val KEY_ACTION_ABORT = "setup.action.abort"
const val KEY_ACTION_REVERT = "setup.action.revert"
const val KEY_PROPS_OFF = "setup.confirm.hold_props_off"

@Composable
private fun wizardStateLabel(state: SetupWizardState): String = stringResource(
    when (state) {
        SetupWizardState.NotStarted -> R.string.cfg_setup_wizard_not_started
        SetupWizardState.Running -> R.string.cfg_setup_state_running
        SetupWizardState.Done -> R.string.cfg_setup_state_done
        SetupWizardState.Failed -> R.string.cfg_setup_state_failed
        SetupWizardState.Aborted -> R.string.cfg_setup_wizard_aborted
        SetupWizardState.Interrupted -> R.string.cfg_setup_wizard_interrupted
    },
)
