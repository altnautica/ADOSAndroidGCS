package com.altnautica.gcs.feature.settings.licences

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.designsystem.component.ErrorState
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.console.ConsoleScaffold
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant

sealed interface NoticesState {
    data object Loading : NoticesState
    data class Ready(val blocks: List<NoticeBlock>) : NoticesState
    data object Missing : NoticesState
}

/** Reads the `THIRD_PARTY_NOTICES.md` the build copies into the app assets. */
@HiltViewModel
class LicencesViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
    private val _state = MutableStateFlow<NoticesState>(NoticesState.Loading)
    val state: StateFlow<NoticesState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                runCatching { context.assets.open(ASSET).bufferedReader().use { it.readText() } }
                    .map { NoticesState.Ready(NoticesMarkdown.parse(it)) }
                    .getOrElse { NoticesState.Missing }
            }
        }
    }

    private val _text = MutableStateFlow<LicenceText?>(null)

    /** The licence text open over the notices, or null. */
    val text: StateFlow<LicenceText?> = _text.asStateFlow()

    fun openText(asset: String) {
        viewModelScope.launch {
            _text.value = withContext(Dispatchers.IO) {
                LicenceText(asset, runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrNull())
            }
        }
    }

    fun closeText() {
        _text.value = null
    }

    companion object {
        const val ASSET = "THIRD_PARTY_NOTICES.md"
    }
}

/** One bundled licence text; [body] is null when the asset is missing from the build. */
data class LicenceText(val asset: String, val body: String?)

@Composable
fun LicencesRoute(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: LicencesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val text by viewModel.text.collectAsStateWithLifecycle()
    val open = text
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = open != null,
        onBackCompleted = viewModel::closeText,
    )
    ConsoleScaffold(
        title = open?.let { licenceTitle(it.asset) } ?: stringResource(R.string.settings_licences),
        onBack = if (open != null) viewModel::closeText else onBack,
        modifier = modifier,
    ) { padding ->
        if (open != null) {
            SelectionContainer {
                Text(
                    open.body ?: stringResource(R.string.settings_licences_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = AdosTheme.colors.fgSecondary,
                    modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(AdosSpacing.S4),
                )
            }
            return@ConsoleScaffold
        }
        when (val s = state) {
            NoticesState.Loading -> LoadingState(stringResource(R.string.settings_loading), Modifier.padding(padding))
            NoticesState.Missing -> ErrorState(
                reason = stringResource(R.string.settings_licences_missing),
                retryLabel = stringResource(R.string.settings_retry),
                onRetry = viewModel::load,
                modifier = Modifier.padding(padding),
            )
            is NoticesState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(AdosSpacing.S4),
                verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                        Text(stringResource(R.string.settings_licences_source_offer), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgSecondary)
                        TextLink(licenceTitle(APP_LICENCE_ASSET)) { viewModel.openText(APP_LICENCE_ASSET) }
                    }
                }
                items(s.blocks) { block -> Block(block, viewModel::openText) }
            }
        }
    }
}

@Composable
private fun TextLink(label: String, onClick: () -> Unit) {
    AdosButton(
        stringResource(R.string.settings_licences_read, label),
        onClick = onClick,
        variant = AdosButtonVariant.Secondary,
    )
}

@Composable
private fun Block(block: NoticeBlock, onOpenText: (String) -> Unit) {
    val colors = AdosTheme.colors
    when (block) {
        is NoticeBlock.Heading -> Text(
            block.text,
            style = if (block.level <= 1) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            color = if (block.level <= 1) colors.fgPrimary else colors.accentPrimary,
        )
        is NoticeBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
        is NoticeBlock.Table -> Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            block.rows.forEach { row ->
                Surface(
                    shape = RoundedCornerShape(AdosRadius.Lg),
                    color = colors.bgSurface,
                    border = BorderStroke(1.dp, colors.borderDefault),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(AdosSpacing.S3), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
                        Text(row.firstOrNull().orEmpty(), style = MaterialTheme.typography.titleSmall)
                        block.header.drop(1).zip(row.drop(1)).filter { it.second.isNotBlank() }.forEach { (h, v) ->
                            Text("$h: $v", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                        }
                        licenceAssetsFor(row).forEach { asset -> TextLink(licenceTitle(asset)) { onOpenText(asset) } }
                    }
                }
            }
        }
    }
}
