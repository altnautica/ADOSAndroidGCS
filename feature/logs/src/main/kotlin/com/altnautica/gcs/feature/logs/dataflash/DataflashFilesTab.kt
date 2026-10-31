package com.altnautica.gcs.feature.logs.dataflash

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.database.TlogFileEntity
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.core.designsystem.component.console.AdosListItem
import com.altnautica.gcs.core.designsystem.component.overlay.AdosConfirmSheet
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.logs.R
import java.io.File

/**
 * Downloaded flight-controller logs, plus the entry to download more from the selected vehicle.
 * The download button stays visible and explains itself when no vehicle is selected.
 */
@Composable
fun DataflashFilesTab(
    files: List<TlogFileEntity>,
    target: DataflashTarget?,
    onDownload: (DeviceId) -> Unit,
    onShareFile: (TlogFileEntity) -> Unit,
    onDeleteFile: (TlogFileEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<TlogFileEntity?>(null) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(AdosSpacing.S4),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
    ) {
        item(key = "download") {
            Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
                AdosButton(
                    label = target?.let { stringResource(R.string.logs_df_download_from, it.name) }
                        ?: stringResource(R.string.logs_df_download_none),
                    onClick = { target?.let { onDownload(it.id) } },
                    enabled = target != null,
                    icon = Icons.Outlined.Download,
                )
                if (target == null) {
                    Text(
                        stringResource(R.string.logs_df_no_selection),
                        style = MaterialTheme.typography.bodySmall,
                        color = AdosTheme.colors.fgSecondary,
                    )
                }
            }
        }
        if (files.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Outlined.Inventory2,
                    title = stringResource(R.string.logs_df_files_empty_title),
                    message = stringResource(R.string.logs_df_files_empty),
                )
            }
        } else {
            items(files, key = { it.id }) { file ->
                LogFileItem(file, onShare = { onShareFile(file) }, onDelete = { pendingDelete = file })
            }
        }
    }
    pendingDelete?.let { file ->
        AdosConfirmSheet(
            title = stringResource(R.string.logs_df_delete_title),
            message = stringResource(R.string.logs_df_delete_message, File(file.path).name),
            onDismiss = { pendingDelete = null },
            danger = true,
        ) {
            HoldToConfirmButton(
                label = stringResource(R.string.logs_delete_confirm),
                holdMs = 800,
                danger = true,
                modifier = Modifier.fillMaxWidth(),
                onConfirmed = {
                    onDeleteFile(file)
                    pendingDelete = null
                },
            )
        }
    }
}

@Composable
private fun LogFileItem(file: TlogFileEntity, onShare: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    AdosListItem(
        headline = File(file.path).name,
        onClick = null,
        supporting = stringResource(
            R.string.logs_file_size,
            Formatter.formatShortFileSize(context, file.sizeBytes),
            DateUtils.formatDateTime(context, file.createdAtMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
        ),
        trailing = {
            Row {
                IconButton(onClick = onShare) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.logs_share))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.logs_delete))
                }
            }
        },
    )
}
