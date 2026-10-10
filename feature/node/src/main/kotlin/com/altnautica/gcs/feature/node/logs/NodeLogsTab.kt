package com.altnautica.gcs.feature.node.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.ProblemState

@Composable
fun NodeLogsTab(nodeId: String, modifier: Modifier = Modifier) {
    val vm = hiltViewModel<NodeLogsViewModel, NodeLogsViewModel.Factory>(key = "logs-$nodeId") { it.create(nodeId) }
    val state by vm.state.collectAsStateWithLifecycle()
    NodeLogsContent(
        state = state,
        onLevel = vm::setLevel,
        onService = vm::setService,
        onApply = vm::applyFilter,
        onRefresh = vm::refresh,
        onMore = vm::loadMore,
        modifier = modifier,
    )
}

@Composable
fun NodeLogsContent(
    state: NodeLogsUiState,
    onLevel: (LogLevel) -> Unit,
    onService: (String) -> Unit,
    onApply: () -> Unit,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.service,
                onValueChange = onService,
                label = { Text(stringResource(R.string.node_logs_service)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onApply() }),
                trailingIcon = {
                    IconButton(onClick = onApply) { Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.node_logs_apply)) }
                },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.node_logs_refresh)) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4),
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        ) {
            LogLevel.entries.forEach { level ->
                FilterChip(selected = state.level == level, onClick = { onLevel(level) }, label = { Text(levelLabel(level)) })
            }
        }
        state.storeWarning?.let {
            StatusChip(StatusLevel.Warning, stringResource(R.string.node_logs_store_warning, it), modifier = Modifier.padding(AdosSpacing.S4))
        }
        when {
            state.problem != null -> ProblemState(state.problem, onRefresh)
            state.loading && state.entries.isEmpty() -> LoadingState(stringResource(R.string.node_loading))
            state.entries.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Search,
                title = stringResource(R.string.node_logs_empty_title),
                message = stringResource(R.string.node_logs_empty_message),
            )
            else -> SelectionContainer {
                LazyColumn(contentPadding = PaddingValues(AdosSpacing.S4), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                    items(state.entries, key = { e -> e.seq ?: e.hashCode().toLong() }) { e -> LogRow(e) }
                    if (state.canLoadMore) {
                        item {
                            AdosButton(stringResource(R.string.node_logs_more), onClick = onMore, variant = AdosButtonVariant.Secondary, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(e: LogEntry) {
    val colors = AdosTheme.colors
    val level = when (e.level) {
        "ERROR", "CRITICAL" -> StatusLevel.Error
        "WARN", "WARNING" -> StatusLevel.Warning
        "DEBUG", "TRACE" -> StatusLevel.Neutral
        else -> StatusLevel.Info
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            StatusChip(level, e.level)
            Text(e.logger, style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary, modifier = Modifier.weight(1f))
            Text(e.timestamp?.substringAfter('T')?.take(TIME_CHARS) ?: "—", style = AdosTypography.numeric.small, color = colors.fgTertiary)
        }
        Text(e.message, style = MaterialTheme.typography.bodySmall)
    }
}

private const val TIME_CHARS = 12

@Composable
private fun levelLabel(level: LogLevel): String = stringResource(
    when (level) {
        LogLevel.All -> R.string.node_logs_level_all
        LogLevel.Debug -> R.string.node_logs_level_debug
        LogLevel.Info -> R.string.node_logs_level_info
        LogLevel.Warning -> R.string.node_logs_level_warning
        LogLevel.Error -> R.string.node_logs_level_error
    },
)
