package com.altnautica.gcs.feature.configure.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.ConfirmSheet
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.ErrorState
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.feature.configure.R
import com.altnautica.gcs.feature.configure.calibration.CalibrationRoute
import com.altnautica.gcs.feature.configure.motor.MotorTestRoute
import com.altnautica.gcs.feature.configure.params.ParamEditorSheet
import com.altnautica.gcs.feature.configure.params.ParamFile
import com.altnautica.gcs.feature.configure.params.ParamRow
import com.altnautica.gcs.feature.configure.params.ParamRowItem
import com.altnautica.gcs.feature.configure.params.ParametersUiState
import com.altnautica.gcs.feature.configure.params.ParametersViewModel

private enum class SetupPage(val label: Int) {
    Calibration(R.string.cfg_setup_calibration),
    FlightModes(R.string.cfg_setup_modes),
    Failsafe(R.string.cfg_setup_failsafe),
    Frame(R.string.cfg_setup_frame),
}

/**
 * Flight-controller setup for one node. MAVLink flight controllers get calibration, flight
 * modes, failsafe and frame/motor pages; MSP flight controllers are configured from the
 * desktop app, so they get an explanation instead.
 */
@Composable
fun SetupRoute(nodeId: DeviceId, fcVariant: FcVariant, modifier: Modifier = Modifier) {
    if (fcVariant == FcVariant.Msp) {
        EmptyState(
            icon = Icons.Outlined.DesktopWindows,
            title = stringResource(R.string.cfg_msp_title),
            message = stringResource(R.string.cfg_msp_message),
            modifier = modifier,
        )
        return
    }
    val params = hiltViewModel<ParametersViewModel, ParametersViewModel.Factory>(
        key = "params-${nodeId.value}",
        creationCallback = { it.create(nodeId.value) },
    )
    val state by params.state.collectAsStateWithLifecycle()
    val pending by params.pendingWrite.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = page) {
            SetupPage.entries.forEachIndexed { i, p ->
                Tab(selected = page == i, onClick = { page = i }, text = { Text(stringResource(p.label)) })
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (SetupPage.entries[page]) {
                SetupPage.Calibration -> CalibrationRoute(nodeId)
                SetupPage.FlightModes -> CuratedParams(state, fcVariant, onRefresh = params::refresh, onWrite = params::requestWrite) { variant, mav ->
                    SetupCatalog.flightModes(variant, mav)?.let { it.slots + it.channelParam }.orEmpty()
                }
                SetupPage.Failsafe -> CuratedParams(state, fcVariant, onRefresh = params::refresh, onWrite = params::requestWrite) { variant, mav ->
                    SetupCatalog.failsafe(variant, mav)
                }
                SetupPage.Frame -> {
                    CuratedParams(state, fcVariant, onRefresh = params::refresh, onWrite = params::requestWrite) { variant, _ ->
                        SetupCatalog.frame(variant)
                    }
                    val motors = (state as? ParametersUiState.Content)?.byName?.mapValues { it.value.value }?.let(SetupCatalog::motorCount)
                    MotorTestRoute(nodeId, motors)
                }
            }
        }
    }
    pending?.let { p ->
        ConfirmSheet(
            title = stringResource(R.string.cfg_param_armed_title),
            message = stringResource(R.string.cfg_param_armed_message, p.name, ParamFile.formatValue(p.value)),
            onDismiss = params::dismissPendingWrite,
            danger = true,
        ) {
            HoldToConfirmButton(
                label = stringResource(R.string.cfg_param_armed_confirm),
                holdMs = 800,
                danger = true,
                onConfirmed = params::confirmPendingWrite,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Parameter rows for a curated name list; names the vehicle does not report are left out. */
@Composable
private fun CuratedParams(
    state: ParametersUiState,
    fcVariant: FcVariant,
    onRefresh: () -> Unit,
    onWrite: (String, String) -> Unit,
    names: (FcVariant, Int?) -> List<String>,
) {
    val colors = AdosTheme.colors
    var editing by remember { mutableStateOf<ParamRow?>(null) }
    when (state) {
        ParametersUiState.NoVehicle -> EmptyState(
            icon = Icons.Outlined.LinkOff,
            title = stringResource(R.string.cfg_no_vehicle_title),
            message = stringResource(R.string.cfg_block_no_vehicle),
        )
        is ParametersUiState.Loading -> Column(Modifier.padding(AdosSpacing.S4)) {
            LoadingState(stringResource(R.string.cfg_param_loading, state.received, state.count))
            if (state.count > 0) LinearProgressIndicator(progress = { state.received / state.count.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
        is ParametersUiState.LoadFailed -> ErrorState(
            reason = stringResource(R.string.cfg_param_load_failed, state.reason),
            retryLabel = stringResource(R.string.cfg_retry),
            onRetry = onRefresh,
        )
        is ParametersUiState.Content -> {
            val rows = names(fcVariant, state.mavType).mapNotNull { state.byName[it] }
            Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
                if (rows.isEmpty()) {
                    Text(
                        stringResource(R.string.cfg_setup_none),
                        color = colors.fgSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(AdosSpacing.S4),
                    )
                }
                rows.forEach { row -> ParamRowItem(row, onClick = { editing = row }) }
            }
        }
    }
    editing?.let { row ->
        ParamEditorSheet(
            row = row,
            onSubmit = { input ->
                onWrite(row.name, input)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}
