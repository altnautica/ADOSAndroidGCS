package com.altnautica.gcs.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.data.home.DisclaimerStore
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether the app may show past the safety disclaimer: null while the stored answer loads. */
@HiltViewModel
class DisclaimerViewModel @Inject constructor(private val store: DisclaimerStore) : ViewModel() {
    val accepted: StateFlow<Boolean?> = store.accepted.map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun accept() {
        viewModelScope.launch { store.accept() }
    }
}

/**
 * Shows [content] only once the current safety disclaimer is accepted; before that the
 * disclaimer fills the screen and nothing behind it (Home, deep links, Fly) is reachable.
 */
@Composable
fun DisclaimerGate(viewModel: DisclaimerViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val accepted by viewModel.accepted.collectAsStateWithLifecycle()
    when (accepted) {
        null -> Unit
        true -> content()
        false -> DisclaimerScreen(onAccept = viewModel::accept)
    }
}

/** The six-section safety disclaimer with an accept checkbox; Accept stays disabled until it is ticked. */
@Composable
internal fun DisclaimerScreen(onAccept: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AdosTheme.colors
    var checked by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .background(colors.bgCanvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(AdosSpacing.S6)
            .testTag("disclaimer"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S4),
    ) {
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S4)) {
            Text(stringResource(R.string.disclaimer_title), style = MaterialTheme.typography.headlineSmall, color = colors.fgPrimary)
            Text(stringResource(R.string.disclaimer_subtitle), style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
            DISCLAIMER_SECTIONS.forEach { (heading, body) ->
                Text(stringResource(heading), style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AdosSize.TouchMin)
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { checked = it })
                    .testTag("disclaimer-accept-check"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(stringResource(R.string.disclaimer_accept_check), style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
            }
            AdosButton(
                stringResource(R.string.disclaimer_accept),
                onClick = onAccept,
                enabled = checked,
                modifier = Modifier.testTag("disclaimer-accept"),
            )
        }
    }
}

/** Heading and body of each disclaimer section, in order. */
internal val DISCLAIMER_SECTIONS: List<Pair<Int, Int>> = listOf(
    R.string.disclaimer_lawful_heading to R.string.disclaimer_lawful_body,
    R.string.disclaimer_military_heading to R.string.disclaimer_military_body,
    R.string.disclaimer_aviation_heading to R.string.disclaimer_aviation_body,
    R.string.disclaimer_export_heading to R.string.disclaimer_export_body,
    R.string.disclaimer_warranty_heading to R.string.disclaimer_warranty_body,
    R.string.disclaimer_license_heading to R.string.disclaimer_license_body,
)
