package com.altnautica.gcs.core.map.offline

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.ErrorState
import com.altnautica.gcs.core.designsystem.component.GlassSurface
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.NumberSettingRow
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.TextSettingRow
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.map.AdosMap
import com.altnautica.gcs.core.map.MapBasemap
import com.altnautica.gcs.core.map.MapProvider
import com.altnautica.gcs.core.map.PmtilesPack
import com.altnautica.gcs.core.map.R
import com.altnautica.gcs.core.map.TileBounds
import com.altnautica.gcs.core.map.rememberAdosMapState
import com.altnautica.gcs.core.map.titleRes
import java.text.NumberFormat

/** Operator actions on the offline maps screen. */
class OfflineMapsActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onStartDraft: () -> Unit,
    val onCancelDraft: () -> Unit,
    val onDraftName: (String) -> Unit,
    val onDraftProvider: (MapProvider) -> Unit,
    val onDraftZoom: (min: Int, max: Int) -> Unit,
    val onDraftBounds: (TileBounds?) -> Unit,
    val onDownload: () -> Unit,
    val onPause: (id: Long, paused: Boolean) -> Unit,
    val onDelete: (id: Long) -> Unit,
    val onImport: (Uri) -> Unit,
    val onDeletePmtiles: (id: String) -> Unit,
    val onMessageShown: () -> Unit,
)

/** Offline basemap management: download provider areas and import raster PMTiles packs. */
@Composable
fun OfflineMapsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OfflineMapsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onBack) {
        OfflineMapsActions(
            onBack = onBack,
            onRetry = viewModel::refresh,
            onStartDraft = viewModel::startDraft,
            onCancelDraft = viewModel::cancelDraft,
            onDraftName = viewModel::setDraftName,
            onDraftProvider = viewModel::setDraftProvider,
            onDraftZoom = viewModel::setDraftZoom,
            onDraftBounds = viewModel::setDraftBounds,
            onDownload = viewModel::download,
            onPause = viewModel::setPaused,
            onDelete = viewModel::delete,
            onImport = viewModel::importPmtiles,
            onDeletePmtiles = viewModel::deletePmtiles,
            onMessageShown = viewModel::messageShown,
        )
    }
    OfflineMapsContent(state, actions, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsContent(state: OfflineMapsUiState, actions: OfflineMapsActions, modifier: Modifier = Modifier) {
    val colors = AdosTheme.colors
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let { messageText(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            actions.onMessageShown()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) actions.onImport(uri)
    }
    Scaffold(
        modifier = modifier,
        containerColor = colors.bgCanvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.offline_maps_title)) },
                navigationIcon = {
                    IconButton(onClick = if (state.draft != null) actions.onCancelDraft else actions.onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.offline_maps_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bgSurface, titleContentColor = colors.fgPrimary),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val draft = state.draft
            when {
                draft != null -> DraftPane(draft, actions)
                state.loading -> LoadingState(stringResource(R.string.offline_maps_loading), Modifier.align(Alignment.Center))
                state.loadError != null -> ErrorState(
                    reason = stringResource(R.string.offline_maps_load_failed, state.loadError),
                    retryLabel = stringResource(R.string.offline_maps_retry),
                    onRetry = actions.onRetry,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> PackList(
                    state = state,
                    actions = actions,
                    onImport = { importLauncher.launch(arrayOf("application/octet-stream", "application/vnd.pmtiles", "*/*")) },
                )
            }
        }
    }
}

@Composable
private fun messageText(message: OfflineMapsMessage): String = when (message) {
    is OfflineMapsMessage.DownloadFailed -> stringResource(R.string.offline_maps_msg_download_failed, message.reason)
    is OfflineMapsMessage.DeleteFailed -> stringResource(R.string.offline_maps_msg_delete_failed, message.reason)
    is OfflineMapsMessage.Imported -> stringResource(R.string.offline_maps_msg_imported, message.name)
    is OfflineMapsMessage.ImportFailed -> when (val e = message.error) {
        PmtilesImportError.NotPmtiles -> stringResource(R.string.offline_maps_msg_import_not_pmtiles)
        PmtilesImportError.VectorTiles -> stringResource(R.string.offline_maps_msg_import_vector)
        is PmtilesImportError.Io -> stringResource(R.string.offline_maps_msg_import_io, e.message)
    }
}

@Composable
private fun PackList(state: OfflineMapsUiState, actions: OfflineMapsActions, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(AdosSpacing.S4),
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        ) {
            AdosButton(stringResource(R.string.offline_maps_download_area), onClick = actions.onStartDraft, icon = Icons.Outlined.Download)
            AdosButton(
                label = stringResource(if (state.importing) R.string.offline_maps_importing else R.string.offline_maps_import),
                onClick = onImport,
                variant = AdosButtonVariant.Secondary,
                enabled = !state.importing,
                icon = Icons.Outlined.FileOpen,
            )
        }
        if (state.packs.isEmpty() && state.pmtiles.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Map,
                title = stringResource(R.string.offline_maps_empty_title),
                message = stringResource(R.string.offline_maps_empty_message),
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (state.packs.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.offline_maps_section_downloads)) }
                items(state.packs, key = { "pack-${it.id}" }) { pack -> PackRow(pack, actions) }
            }
            if (state.pmtiles.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.offline_maps_section_packs)) }
                items(state.pmtiles, key = { "pmtiles-${it.id}" }) { pack -> PmtilesRow(pack, actions) }
            }
        }
    }
}

@Composable
private fun PackRow(pack: OfflinePack, actions: OfflineMapsActions) {
    val colors = AdosTheme.colors
    val context = LocalContext.current
    val size = Formatter.formatShortFileSize(context, pack.status.completedBytes)
    val status = pack.status
    val statusText = when {
        status.error != null -> stringResource(R.string.offline_maps_status_error, status.error)
        status.complete -> stringResource(R.string.offline_maps_status_complete, size)
        status.downloading -> status.fraction?.let {
            stringResource(R.string.offline_maps_status_downloading, (it * 100).toInt(), size)
        } ?: stringResource(R.string.offline_maps_status_downloading_unknown, size)
        else -> stringResource(R.string.offline_maps_status_paused, size)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
            Text(pack.name, style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
            Text(
                stringResource(R.string.offline_maps_pack_zoom, providerLabel(pack.provider), pack.minZoom, pack.maxZoom),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
            )
            Text(statusText, style = MaterialTheme.typography.bodySmall, color = if (status.error != null) colors.statusError else colors.fgSecondary)
            if (!status.complete && status.fraction != null) {
                LinearProgressIndicator(
                    progress = { status.fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accentPrimary,
                    trackColor = colors.borderDefault,
                )
            }
        }
        if (!status.complete) {
            val paused = !status.downloading
            IconButton(onClick = { actions.onPause(pack.id, !paused) }) {
                Icon(
                    if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    contentDescription = stringResource(if (paused) R.string.offline_maps_resume else R.string.offline_maps_pause),
                    tint = colors.fgPrimary,
                )
            }
        }
        IconButton(onClick = { actions.onDelete(pack.id) }) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.offline_maps_delete_named, pack.name), tint = colors.fgSecondary)
        }
    }
}

@Composable
private fun PmtilesRow(pack: PmtilesPack, actions: OfflineMapsActions) {
    val colors = AdosTheme.colors
    val size = Formatter.formatShortFileSize(LocalContext.current, pack.sizeBytes)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
            Text(pack.name, style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
            Text(
                stringResource(R.string.offline_maps_pack_details, pack.header.minZoom, pack.header.maxZoom, size),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
            )
        }
        IconButton(onClick = { actions.onDeletePmtiles(pack.id) }) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.offline_maps_delete_named, pack.name), tint = colors.fgSecondary)
        }
    }
}

@Composable
private fun providerLabel(provider: MapProvider): String = stringResource(provider.titleRes)

@Composable
private fun DraftPane(draft: PackDraft, actions: OfflineMapsActions) {
    val mapState = rememberAdosMapState(initialZoom = 13.0)
    LaunchedEffect(mapState) {
        snapshotFlow { mapState.visibleBounds }.collect { actions.onDraftBounds(it) }
    }
    val colors = AdosTheme.colors
    val providerLabels = MapProvider.entries.associateWith { stringResource(it.titleRes) }
    Box(Modifier.fillMaxSize()) {
        AdosMap(
            modifier = Modifier.fillMaxSize(),
            state = mapState,
            basemap = MapBasemap.Online(draft.provider),
        )
        GlassSurface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(AdosSpacing.S3),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
                Text(stringResource(R.string.offline_maps_draft_hint), style = MaterialTheme.typography.bodySmall, color = colors.hudInk2)
                TextSettingRow(
                    title = stringResource(R.string.offline_maps_draft_name),
                    value = draft.name,
                    onValueChange = actions.onDraftName,
                    placeholder = stringResource(R.string.offline_maps_draft_name_placeholder),
                )
                SelectSettingRow(
                    title = stringResource(R.string.offline_maps_draft_provider),
                    options = MapProvider.entries,
                    selected = draft.provider,
                    optionLabel = { providerLabels.getValue(it) },
                    onSelect = actions.onDraftProvider,
                )
                NumberSettingRow(
                    title = stringResource(R.string.offline_maps_draft_min_zoom),
                    value = draft.minZoom,
                    onValueChange = { actions.onDraftZoom(it, draft.maxZoom) },
                    range = 0..draft.provider.maxZoom,
                    decrementDescription = stringResource(R.string.offline_maps_draft_decrease),
                    incrementDescription = stringResource(R.string.offline_maps_draft_increase),
                )
                NumberSettingRow(
                    title = stringResource(R.string.offline_maps_draft_max_zoom),
                    value = draft.maxZoom,
                    onValueChange = { actions.onDraftZoom(draft.minZoom, it) },
                    range = draft.minZoom..draft.provider.maxZoom,
                    decrementDescription = stringResource(R.string.offline_maps_draft_decrease),
                    incrementDescription = stringResource(R.string.offline_maps_draft_increase),
                )
                val estimate = draft.estimate
                val estimateText = when {
                    estimate == null -> stringResource(R.string.offline_maps_draft_estimate_pending)
                    draft.tooLarge -> stringResource(R.string.offline_maps_draft_too_large)
                    else -> stringResource(
                        R.string.offline_maps_draft_estimate,
                        NumberFormat.getIntegerInstance().format(estimate.tileCount),
                        Formatter.formatShortFileSize(LocalContext.current, estimate.estimatedBytes),
                    )
                }
                Text(
                    estimateText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (draft.tooLarge) colors.statusWarning else colors.hudInk,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), modifier = Modifier.padding(top = 4.dp)) {
                    AdosButton(
                        stringResource(R.string.offline_maps_draft_download),
                        onClick = actions.onDownload,
                        enabled = draft.canDownload,
                        icon = Icons.Outlined.Download,
                    )
                    AdosButton(
                        stringResource(R.string.offline_maps_draft_cancel),
                        onClick = actions.onCancelDraft,
                        variant = AdosButtonVariant.Secondary,
                    )
                }
            }
        }
    }
}
