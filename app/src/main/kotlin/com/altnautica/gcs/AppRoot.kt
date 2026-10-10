package com.altnautica.gcs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.EmptyState

/** Fleet start destination shown before any node is connected. */
@Composable
fun AppRoot() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        EmptyState(
            icon = Icons.Outlined.Hub,
            title = stringResource(R.string.fleet_empty_title),
            message = stringResource(R.string.fleet_empty_message),
            modifier = Modifier.safeDrawingPadding(),
        )
    }
}
