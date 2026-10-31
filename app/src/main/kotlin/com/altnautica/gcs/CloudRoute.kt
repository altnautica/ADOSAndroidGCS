package com.altnautica.gcs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.cloud.ui.CloudSettingsRoute
import com.altnautica.gcs.core.designsystem.component.console.ConsoleScaffold
import com.altnautica.gcs.radio.rx.ui.PhoneRadioRoute

/** Settings → Cloud as a full route with its own back affordance. */
@Composable
fun CloudRoute(onBack: () -> Unit) {
    ShellRoute(stringResource(R.string.nav_cloud), onBack) { CloudSettingsRoute(it) }
}

/** Settings / Connect → Phone radio as a full route. */
@Composable
fun PhoneRadioShellRoute(onBack: () -> Unit) {
    ShellRoute(stringResource(com.altnautica.gcs.radio.rx.R.string.phone_radio_title), onBack) { PhoneRadioRoute(it) }
}

@Composable
private fun ShellRoute(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    ConsoleScaffold(title = title, onBack = onBack) { padding ->
        content(Modifier.fillMaxSize().padding(padding))
    }
}
