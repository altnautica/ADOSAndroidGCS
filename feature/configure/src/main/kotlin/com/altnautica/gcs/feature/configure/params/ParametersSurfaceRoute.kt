package com.altnautica.gcs.feature.configure.params

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.console.ConsoleScaffold
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.configure.R
import com.altnautica.gcs.feature.configure.setup.SetupSurfaceViewModel
import com.altnautica.gcs.feature.configure.setup.SurfaceNodeState

/** The Parameters surface for [nodeId]: a console frame around the parameter list. */
@Composable
fun ParametersSurfaceRoute(nodeId: DeviceId, onBack: () -> Unit) {
    val vm = hiltViewModel<SetupSurfaceViewModel, SetupSurfaceViewModel.Factory>(
        key = "params-surface-${nodeId.value}",
        creationCallback = { it.create(nodeId.value) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val name = (state as? SurfaceNodeState.Ready)?.name ?: nodeId.value
    ConsoleScaffold(
        title = stringResource(R.string.cfg_params_surface_title, name),
        onBack = onBack,
    ) { padding ->
        ParametersRoute(nodeId = nodeId, modifier = Modifier.fillMaxSize().padding(padding))
    }
}
