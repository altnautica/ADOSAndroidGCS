package com.altnautica.gcs.core.extensions.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.extensions.host.SlotMount
import com.altnautica.gcs.core.model.DeviceId

/**
 * Extension pages a node offers in [slot] (`node.detail.tab`, `node.agent.page` or
 * `node.surface`), filtered by the node's profile and granted capabilities, ordered for
 * display. Use each entry's `contribution.title`/`icon`/`section`/`group` to place it.
 */
@Composable
fun rememberExtensionNodePages(node: DeviceId, slot: String): State<List<SlotMount>> {
    val vm: ExtensionSlotViewModel = hiltViewModel(key = "extension-pages:${node.value}:$slot")
    LaunchedEffect(vm, node) { vm.ensureLoaded(node) }
    val flow = remember(vm, node, slot) { vm.mounts(node, slot, null) }
    return flow.collectAsStateWithLifecycle(emptyList())
}

/** Hosts one extension page; inline modules show the desktop-only notice. */
@Composable
fun ExtensionNodePage(node: DeviceId, page: SlotMount, modifier: Modifier = Modifier) {
    val vm: ExtensionSlotViewModel = hiltViewModel(key = "extension-page:${node.value}:${page.key}")
    ExtensionFrame(
        node = node,
        pluginId = page.pluginId,
        mountKey = page.key,
        modifier = modifier.fillMaxSize(),
    )
    ExtensionPromptHost(vm)
}
