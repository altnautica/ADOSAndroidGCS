package com.altnautica.gcs.feature.cockpit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.feature.cockpit.chrome.StatusModel
import com.altnautica.gcs.feature.cockpit.chrome.StatusPill
import com.altnautica.gcs.feature.cockpit.tips.tipAnchor

/**
 * The flight screen's status pill: the active items, the neutral no-link face before the
 * first pilot frame or heartbeat, and the flash until the list is opened, which
 * acknowledges what it shows. [status] learns where the list hangs.
 */
@Composable
internal fun FlightStatusPill(vm: CockpitViewModel, status: StatusListState) {
    val items by vm.statusItems.collectAsStateWithLifecycle()
    val waiting by vm.statusWaiting.collectAsStateWithLifecycle()
    var acknowledged by remember { mutableStateOf(emptySet<String>()) }
    StatusPill(
        StatusModel.pill(items, acknowledged, waiting),
        onOpen = {
            acknowledged = items.mapTo(HashSet()) { it.key }
            vm.setStatusList(true)
        },
        modifier = Modifier.tipAnchor("status_pill").onGloballyPositioned { status.pillX = it.boundsInRoot().left },
    )
}
