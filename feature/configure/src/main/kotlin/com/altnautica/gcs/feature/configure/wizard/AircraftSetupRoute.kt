package com.altnautica.gcs.feature.configure.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
 * (and [step]) open straight into one wizard or step.
 */
@Composable
fun AircraftSetupRoute(nodeId: DeviceId, onBack: () -> Unit, wizard: String? = null, step: String? = null) {
    val vm = hiltViewModel<WizardViewModel, WizardViewModel.Factory>(
        key = "aircraft-setup-${nodeId.value}-$wizard-$step",
        creationCallback = { it.create(nodeId.value, wizard, step) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalSetupText provides vm.text) {
        AircraftSetupScreen(
            state = state,
            onBack = onBack,
            onOpen = vm::open,
            onStart = vm::start,
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
                state.setup.overview == null && state.setup.problem != null -> ProblemText(state.setup.problem, Modifier.align(Alignment.Center))
                state.setup.overview == null -> LoadingState(stringResource(R.string.cfg_surface_loading), Modifier.align(Alignment.Center))
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AdosSpacing.S4).widthIn(max = 720.dp),
                    verticalArrangement = Arrangement.spacedBy(AdosSpacing.S4),
                ) {
                    state.setup.problem?.let { ProblemText(it) }
                    if (state.focus == null) {
                        WizardList(state, onOpen, onStart, onRunStep, onRevert)
                    } else {
                        WizardPane(state, onStart, onAction, onAbort, onRevert)
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardPane(
    state: WizardUiState,
    onStart: (String) -> Unit,
    onAction: (com.altnautica.gcs.core.agent.fcsetup.SetupStep, String, JsonObject?) -> Unit,
    onAbort: () -> Unit,
    onRevert: (String) -> Unit,
) {
    val text = LocalSetupText.current
    val focus = state.focus ?: return
    val wizard = state.wizard
    val step = state.step
    Text(wizardTitle(focus.wizard), style = MaterialTheme.typography.headlineSmall, color = AdosTheme.colors.fgPrimary)
    when {
        state.showResults -> {
            Text(stringResource(R.string.cfg_setup_wizard_done), color = AdosTheme.colors.statusSuccess)
            wizard?.steps?.forEach { s ->
                s.result?.takeIf { it.isNotEmpty() }?.let {
                    Text(text.format(s.titleKey, s.args.textArgs()), style = MaterialTheme.typography.titleSmall, color = AdosTheme.colors.fgPrimary)
                    ResultCard(it)
                }
            }
            Snapshots(state, onRevert)
        }
        step != null -> {
            WizardStepView(step, busy = state.setup.busy, onAction = { a, args -> onAction(step, a, args) })
            if (wizard?.state == SetupWizardState.Running || step.state == SetupStepState.Running) {
                AdosButton(
                    LocalSetupText.current.format(KEY_ACTION_ABORT, emptyMap()),
                    onClick = onAbort,
                    variant = AdosButtonVariant.Danger,
                    enabled = !state.setup.busy,
                )
            }
        }
        else -> {
            wizard?.blockers?.forEach { Text(text.format(it, emptyMap()), color = AdosTheme.colors.statusError) }
            AdosButton(
                stringResource(R.string.cfg_setup_start),
                onClick = { onStart(focus.wizard) },
                enabled = wizard?.available == true && !state.setup.busy,
            )
        }
    }
}

@Composable
private fun WizardList(
    state: WizardUiState,
    onOpen: (String, String?) -> Unit,
    onStart: (String) -> Unit,
    onRunStep: (String, String) -> Unit,
    onRevert: (String) -> Unit,
) {
    val text = LocalSetupText.current
    val colors = AdosTheme.colors
    val overview = state.setup.overview ?: return
    listOf(FcSetupApi.WIZARD_GROUND, FcSetupApi.WIZARD_AIR).forEach { id ->
        val wizard: SetupWizard = state.setup.wizard(id) ?: return@forEach
        SectionHeader(wizardTitle(id))
        Text(wizardStateLabel(wizard.state), color = if (wizard.completed) colors.statusSuccess else colors.fgSecondary)
        wizard.blockers.forEach { Text(text.format(it, emptyMap()), color = colors.statusError) }
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            val resumable = wizard.state == SetupWizardState.Running
            AdosButton(
                stringResource(if (resumable) R.string.cfg_setup_continue else R.string.cfg_setup_start),
                onClick = { if (resumable) onOpen(id, null) else onStart(id) },
                enabled = wizard.available && !state.setup.busy,
            )
            if (wizard.completed) {
                AdosButton(stringResource(R.string.cfg_setup_view_results), onClick = { onOpen(id, null) }, variant = AdosButtonVariant.Secondary)
            }
        }
        wizard.steps.forEach { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text.format(s.titleKey, s.args.textArgs()), color = colors.fgPrimary)
                    Text(stateLabel(s.state), style = MaterialTheme.typography.labelSmall, color = colors.fgSecondary)
                }
                AdosButton(
                    stringResource(R.string.cfg_setup_run_step),
                    onClick = { onRunStep(id, s.step) },
                    variant = AdosButtonVariant.Secondary,
                    enabled = wizard.available && !state.setup.busy && overview.running == null,
                )
            }
        }
    }
    Snapshots(state, onRevert)
}

@Composable
private fun Snapshots(state: WizardUiState, onRevert: (String) -> Unit) {
    val snapshots = state.setup.overview?.report?.snapshots.orEmpty()
    if (snapshots.isEmpty()) return
    SectionHeader(stringResource(R.string.cfg_setup_snapshots))
    snapshots.forEach { snap ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(snap.ts ?: snap.file, Modifier.weight(1f), color = AdosTheme.colors.fgPrimary)
            HoldToConfirmButton(
                label = LocalSetupText.current.format(KEY_ACTION_REVERT, emptyMap()),
                holdMs = SETUP_HOLD_MS,
                onConfirmed = { onRevert(snap.file) },
                enabled = !state.setup.busy,
                danger = true,
            )
        }
    }
}

@Composable
private fun ProblemText(problem: SetupProblem, modifier: Modifier = Modifier) {
    val text = problem.key?.let { LocalSetupText.current.format(it, emptyMap()) } ?: stringResource(
        when (problem.local) {
            SetupLocalProblem.Unauthorized -> R.string.cfg_setup_err_unauthorized
            SetupLocalProblem.Unsupported -> R.string.cfg_setup_err_unsupported
            SetupLocalProblem.Armed -> R.string.cfg_setup_err_armed
            SetupLocalProblem.Unreachable, null -> R.string.cfg_setup_err_unreachable
        },
    )
    Text(text, modifier, color = AdosTheme.colors.statusError)
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
    },
)
