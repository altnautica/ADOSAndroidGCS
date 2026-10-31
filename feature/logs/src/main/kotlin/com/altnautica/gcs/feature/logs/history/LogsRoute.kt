package com.altnautica.gcs.feature.logs.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.console.ConsoleButton
import com.altnautica.gcs.core.designsystem.component.console.ConsoleScaffold
import com.altnautica.gcs.core.designsystem.component.console.ConsoleTwoPane
import com.altnautica.gcs.core.designsystem.component.console.Hint
import com.altnautica.gcs.core.designsystem.component.console.isLandscape
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.logs.R
import com.altnautica.gcs.feature.logs.dataflash.DataflashFilesTab
import com.altnautica.gcs.feature.logs.dataflash.DataflashTargetViewModel
import com.altnautica.gcs.feature.logs.detail.FlightDetailPane
import com.altnautica.gcs.feature.logs.share.shareLogFile
import java.io.File

/**
 * The Logs surface: recorded flights and downloaded flight-controller logs, as header tabs.
 * Landscape shows the selected flight beside the list; portrait opens it through [onOpenFlight].
 */
@Composable
fun LogsRoute(
    onBack: () -> Unit,
    onOpenFlight: (sessionId: Long) -> Unit,
    onOpenDataflash: (DeviceId) -> Unit,
    onReplay: (tlogPath: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FlightHistoryViewModel = hiltViewModel(),
    targetViewModel: DataflashTargetViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val target by targetViewModel.target.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val landscape = isLandscape()
    var tab by rememberSaveable { mutableIntStateOf(TAB_FLIGHTS) }
    var selectedFlight by rememberSaveable { mutableStateOf<Long?>(null) }
    val content = state as? FlightHistoryUiState.Content
    val flights = content?.flights.orEmpty()
    // Landscape always shows a flight when one exists: the stored choice, else the newest.
    val shownFlight = selectedFlight?.takeIf { id -> flights.any { it.session.id == id } }
        ?: flights.firstOrNull()?.session?.id

    ConsoleScaffold(
        title = stringResource(R.string.logs_surface_title),
        onBack = onBack,
        modifier = modifier,
        tabs = listOf(stringResource(R.string.logs_tab_flights), stringResource(R.string.logs_tab_dataflash)),
        selectedTab = tab,
        onSelectTab = { tab = it },
        hints = listOf(
            Hint(ConsoleButton.Confirm, stringResource(R.string.logs_hint_open)),
            Hint(ConsoleButton.Back, stringResource(R.string.logs_hint_back)),
            Hint(ConsoleButton.PrevTab, stringResource(R.string.logs_hint_tabs)),
        ),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (tab == TAB_FLIGHTS) {
                ConsoleTwoPane(
                    list = {
                        FlightList(
                            state = state,
                            selectedId = if (landscape) shownFlight else null,
                            onOpenFlight = { id -> if (landscape) selectedFlight = id else onOpenFlight(id) },
                            onDeleteFlight = viewModel::deleteFlight,
                            onRetry = viewModel::retry,
                        )
                    },
                    detail = {
                        val id = shownFlight
                        if (id != null) {
                            FlightDetailPane(sessionId = id, onReplay = onReplay)
                        } else {
                            EmptyState(
                                icon = Icons.Outlined.Timeline,
                                title = stringResource(R.string.logs_pick_flight_title),
                                message = stringResource(R.string.logs_pick_flight),
                            )
                        }
                    },
                    showDetailInPortrait = false,
                )
            } else {
                DataflashFilesTab(
                    files = content?.dataflashLogs.orEmpty(),
                    target = target,
                    onDownload = onOpenDataflash,
                    onShareFile = { shareLogFile(context, File(it.path)) },
                    onDeleteFile = viewModel::deleteLogFile,
                )
            }
        }
    }
}

private const val TAB_FLIGHTS = 0
