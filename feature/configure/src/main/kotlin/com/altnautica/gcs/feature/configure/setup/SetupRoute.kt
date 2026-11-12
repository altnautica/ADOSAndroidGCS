package com.altnautica.gcs.feature.configure.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.ErrorState
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.console.AdosListItem
import com.altnautica.gcs.core.designsystem.component.console.ConsoleTwoPane
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.feature.configure.R
import com.altnautica.gcs.feature.configure.calibration.CalibrationRoute
import com.altnautica.gcs.feature.configure.motor.MotorTestRoute
import com.altnautica.gcs.feature.configure.params.ParamEditorSheet
import com.altnautica.gcs.feature.configure.params.PendingWriteSheet
import com.altnautica.gcs.feature.configure.params.RebootBanner
import com.altnautica.gcs.feature.configure.params.ParamRow
import com.altnautica.gcs.feature.configure.params.ParamRowItem
import com.altnautica.gcs.feature.configure.params.ParametersUiState
import com.altnautica.gcs.feature.configure.params.ParametersViewModel
import com.altnautica.gcs.feature.configure.params.paramLoadReason
import com.altnautica.gcs.feature.configure.params.paramMessageText

/** The setup sections, in list order. */
enum class SetupSection(val label: Int, val icon: ImageVector) {
    AircraftSetup(R.string.cfg_setup_aircraft, Icons.Outlined.FlightTakeoff),
    Calibration(R.string.cfg_setup_calibration, Icons.Outlined.Explore),
    FlightModes(R.string.cfg_setup_modes, Icons.Outlined.FlightTakeoff),
    Failsafe(R.string.cfg_setup_failsafe, Icons.Outlined.Shield),
    Frame(R.string.cfg_setup_frame, Icons.Outlined.Category),
    MotorTest(R.string.cfg_setup_motors, Icons.Outlined.Speed),
}

/**
 * Section list and the open [section] for a MAVLink flight controller, with the write
 * results of the curated parameter sections shown in a snackbar.
 */
@Composable
internal fun SetupPanes(
    nodeId: DeviceId,
    fcVariant: FcVariant,
    section: SetupSection,
    showDetailInPortrait: Boolean,
    onSelect: (SetupSection) -> Unit,
    onOpenAircraftSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val params = hiltViewModel<ParametersViewModel, ParametersViewModel.Factory>(
        key = "params-${nodeId.value}",
        creationCallback = { it.create(nodeId.value) },
    )
    val state by params.state.collectAsStateWithLifecycle()
    val pending by params.pendingWrite.collectAsStateWithLifecycle()
    val message by params.message.collectAsStateWithLifecycle()
    val writing by params.writing.collectAsStateWithLifecycle()
    val rebootPending by params.rebootPending.collectAsStateWithLifecycle()
    // Write results from the curated parameter sections; the message is consumed once shown.
    val snackbar = remember { SnackbarHostState() }
    val messageText = message?.let { paramMessageText(it, (state as? ParametersUiState.Content)?.byName.orEmpty()).first }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            params.consumeMessage()
        }
    }
    Box(modifier.fillMaxSize()) {
        ConsoleTwoPane(
            showDetailInPortrait = showDetailInPortrait,
            list = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = AdosSpacing.S2)) {
                    SetupSection.entries.forEach { s ->
                        AdosListItem(
                            headline = stringResource(s.label),
                            onClick = { onSelect(s) },
                            selected = s == section,
                            leading = { Icon(s.icon, contentDescription = null, tint = AdosTheme.colors.fgSecondary) },
                        )
                    }
                }
            },
            detail = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    // Frame, flight-mode and failsafe writes and sensor calibrations can need a reboot: offer it here.
                    val content = state as? ParametersUiState.Content
                    if (rebootPending && content != null) {
                        RebootBanner(armed = content.armed, linkFresh = content.linkFresh, onReboot = params::reboot)
                    }
                    when (section) {
                        SetupSection.AircraftSetup -> Column(Modifier.padding(AdosSpacing.S4), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
                            Text(stringResource(R.string.cfg_setup_aircraft_body), color = AdosTheme.colors.fgSecondary)
                            AdosButton(stringResource(R.string.cfg_setup_aircraft_open), onClick = onOpenAircraftSetup)
                        }
                        SetupSection.Calibration -> CalibrationRoute(nodeId, onRebootAdvised = params::markRebootNeeded)
                        SetupSection.FlightModes -> CuratedParams(state, fcVariant, writing, onRefresh = params::refresh, onWrite = params::requestWrite) { variant, mav ->
                            SetupCatalog.flightModes(variant, mav)?.let { it.slots + it.channelParam }.orEmpty()
                        }
                        SetupSection.Failsafe -> CuratedParams(state, fcVariant, writing, onRefresh = params::refresh, onWrite = params::requestWrite) { variant, mav ->
                            SetupCatalog.failsafe(variant, mav)
                        }
                        SetupSection.Frame -> CuratedParams(state, fcVariant, writing, onRefresh = params::refresh, onWrite = params::requestWrite) { variant, _ ->
                            SetupCatalog.frame(variant)
                        }
                        SetupSection.MotorTest -> {
                            val motors = (state as? ParametersUiState.Content)?.byName?.mapValues { it.value.value }?.let(SetupCatalog::motorCount)
                            MotorTestRoute(nodeId, motors)
                        }
                    }
                }
            },
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    pending?.let { p ->
        PendingWriteSheet(p, onDismiss = params::dismissPendingWrite, onConfirm = params::confirmPendingWrite)
    }
}

/** Parameter rows for a curated name list; names the vehicle does not report are left out. */
@Composable
private fun CuratedParams(
    state: ParametersUiState,
    fcVariant: FcVariant,
    writing: String?,
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
            reason = stringResource(R.string.cfg_param_load_failed, paramLoadReason(state.reason)),
            retryLabel = stringResource(R.string.cfg_retry),
            onRetry = onRefresh,
        )
        is ParametersUiState.Content -> {
            val rows = names(fcVariant, state.mavType).mapNotNull { state.byName[it] }
            Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
                writing?.let {
                    StatusChip(
                        level = StatusLevel.Info,
                        label = stringResource(R.string.cfg_param_writing, it),
                        modifier = Modifier.padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S1),
                    )
                }
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
            writing = writing,
        )
    }
}
