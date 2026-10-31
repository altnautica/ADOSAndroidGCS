package com.altnautica.gcs.feature.configure.params

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.core.designsystem.component.console.ConsoleButton
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommand
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommandHandler
import com.altnautica.gcs.core.designsystem.component.console.ConsoleScaffold
import com.altnautica.gcs.core.designsystem.component.console.ConsoleTwoPane
import com.altnautica.gcs.core.designsystem.component.console.Hint
import com.altnautica.gcs.core.designsystem.component.console.isLandscape
import com.altnautica.gcs.core.designsystem.component.overlay.AdosConfirmSheet
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenu
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenuItem
import com.altnautica.gcs.core.designsystem.component.overlay.adosMenuAnchor
import com.altnautica.gcs.core.designsystem.component.overlay.rememberAdosMenuState
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.configure.R
import com.altnautica.gcs.feature.configure.setup.SetupSurfaceViewModel
import com.altnautica.gcs.feature.configure.setup.SurfaceNodeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Parameters surface for [nodeId]: the grouped list on the left and the editor for the
 * selected parameter on the right (a sheet in portrait). L1/R1 jump between groups.
 */
@Composable
fun ParametersSurfaceRoute(nodeId: DeviceId, onBack: () -> Unit) {
    val node = hiltViewModel<SetupSurfaceViewModel, SetupSurfaceViewModel.Factory>(
        key = "params-surface-${nodeId.value}",
        creationCallback = { it.create(nodeId.value) },
    )
    val viewModel = hiltViewModel<ParametersViewModel, ParametersViewModel.Factory>(
        key = "params-${nodeId.value}",
        creationCallback = { it.create(nodeId.value) },
    )
    val nodeState by node.state.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pendingWrite.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val review by viewModel.importReview.collectAsStateWithLifecycle()
    val rebootPending by viewModel.rebootPending.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val saveMenu = rememberAdosMenuState()
    val landscape = isLandscape()
    var exportFormat by remember { mutableStateOf(ParamFileFormat.Qgc) }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val content = state as? ParametersUiState.Content
    val selected = selectedName?.let { content?.byName?.get(it) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = viewModel.exportText(exportFormat)
        if (uri != null && text != null) scope.launch { viewModel.fileWritten(writeUri(context, uri, text)) }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                readUri(context, uri).fold(viewModel::importText) { viewModel.fileUnreadable(it.message) }
            }
        }
    }
    val save = { format: ParamFileFormat ->
        exportFormat = format
        saveLauncher.launch(if (format == ParamFileFormat.Qgc) "vehicle.params" else "vehicle.param")
    }

    ConsoleCommandHandler(enabled = content != null) { command ->
        when (command) {
            ConsoleCommand.PrevTab, ConsoleCommand.NextTab -> {
                val headers = headerIndices(content?.rows.orEmpty().grouped())
                groupJumpTarget(headers, listState.firstVisibleItemIndex, forward = command == ConsoleCommand.NextTab)
                    ?.let { scope.launch { listState.animateScrollToItem(it) } }
                true
            }
            ConsoleCommand.Menu -> {
                saveMenu.expanded = true
                true
            }
            else -> false
        }
    }

    ConsoleScaffold(
        title = stringResource(R.string.cfg_params_surface_title, (nodeState as? SurfaceNodeState.Ready)?.name ?: nodeId.value),
        onBack = onBack,
        actions = {
            IconButton(onClick = viewModel::refresh, enabled = content?.loading == false) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.cfg_param_refresh))
            }
            IconButton(onClick = { saveMenu.expanded = true }, enabled = content != null, modifier = Modifier.adosMenuAnchor(saveMenu)) {
                Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.cfg_param_save_file))
            }
            IconButton(onClick = { openLauncher.launch(arrayOf("*/*")) }, enabled = content != null) {
                Icon(Icons.Outlined.FileOpen, contentDescription = stringResource(R.string.cfg_param_load_file))
            }
        },
        hints = listOf(
            Hint(ConsoleButton.Confirm, stringResource(R.string.cfg_hint_edit)),
            Hint(ConsoleButton.Back, stringResource(R.string.cfg_back)),
            Hint(ConsoleButton.NextTab, stringResource(R.string.cfg_hint_group)),
            Hint(ConsoleButton.Menu, stringResource(R.string.cfg_param_save_file)),
        ),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (rebootPending && content != null) {
                RebootBanner(armed = content.armed, linkFresh = content.linkFresh, onReboot = viewModel::reboot)
            }
            ConsoleTwoPane(
                showDetailInPortrait = false,
                list = {
                    ParametersListPane(
                        state = state,
                        message = message,
                        listState = listState,
                        selectedName = selectedName,
                        onSelect = { selectedName = it.name },
                        onFilter = viewModel::setFilter,
                        onRefresh = viewModel::refresh,
                        onDismissMessage = viewModel::consumeMessage,
                    )
                },
                detail = {
                    if (selected == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.cfg_param_pick), color = AdosTheme.colors.fgSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        ParamEditorContent(
                            row = selected,
                            onSubmit = { viewModel.requestWrite(selected.name, it) },
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AdosSpacing.S4),
                        )
                    }
                },
            )
        }
    }

    AdosMenu(
        saveMenu,
        listOf(
            AdosMenuItem(stringResource(R.string.cfg_param_save_qgc)) { save(ParamFileFormat.Qgc) },
            AdosMenuItem(stringResource(R.string.cfg_param_save_ardupilot)) { save(ParamFileFormat.MissionPlanner) },
        ),
    )
    if (!landscape && selected != null) {
        ParamEditorSheet(
            row = selected,
            onSubmit = { input ->
                viewModel.requestWrite(selected.name, input)
                selectedName = null
            },
            onDismiss = { selectedName = null },
        )
    }
    pending?.let { p ->
        AdosConfirmSheet(
            title = stringResource(R.string.cfg_param_armed_title),
            message = stringResource(R.string.cfg_param_armed_message, p.name, ParamFile.formatValue(p.value)),
            onDismiss = viewModel::dismissPendingWrite,
            danger = true,
        ) {
            HoldToConfirmButton(
                label = stringResource(R.string.cfg_param_armed_confirm),
                holdMs = 800,
                danger = true,
                onConfirmed = viewModel::confirmPendingWrite,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    review?.let {
        ImportReviewSheet(
            it,
            armed = content?.armed == true,
            onToggle = viewModel::toggleImportRow,
            onApply = viewModel::applyImport,
            onDismiss = viewModel::dismissImport,
        )
    }
}

/** Writes [text] to [uri]; a provider that hands back no stream is a failure. */
private suspend fun writeUri(context: Context, uri: Uri, text: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val stream = context.contentResolver.openOutputStream(uri) ?: error("no output stream")
        stream.bufferedWriter().use { it.write(text) }
    }
}

private suspend fun readUri(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val stream = context.contentResolver.openInputStream(uri) ?: error("no input stream")
        stream.bufferedReader().use { it.readText() }
    }
}
