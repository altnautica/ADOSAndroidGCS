package com.altnautica.gcs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.cloud.ui.CloudSettingsRoute
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShellRoute(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        content(Modifier.fillMaxSize().padding(padding))
    }
}
