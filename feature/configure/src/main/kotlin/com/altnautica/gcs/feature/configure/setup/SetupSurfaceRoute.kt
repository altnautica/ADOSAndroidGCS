package com.altnautica.gcs.feature.configure.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.console.ConsoleScaffold
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.configure.R

/** The Setup surface for [nodeId]: a console frame around the setup pages. */
@Composable
fun SetupSurfaceRoute(nodeId: DeviceId, onBack: () -> Unit) {
    val vm = hiltViewModel<SetupSurfaceViewModel, SetupSurfaceViewModel.Factory>(
        key = "setup-surface-${nodeId.value}",
        creationCallback = { it.create(nodeId.value) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val ready = state as? SurfaceNodeState.Ready
    ConsoleScaffold(
        title = stringResource(R.string.cfg_setup_surface_title, ready?.name ?: nodeId.value),
        onBack = onBack,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (ready == null) {
                LoadingState(stringResource(R.string.cfg_surface_loading), Modifier.align(Alignment.Center))
            } else {
                SetupRoute(nodeId = nodeId, fcVariant = ready.fcVariant)
            }
        }
    }
}
