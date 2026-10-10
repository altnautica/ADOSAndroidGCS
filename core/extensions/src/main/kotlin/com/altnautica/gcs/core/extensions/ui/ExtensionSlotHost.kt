package com.altnautica.gcs.core.extensions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.designsystem.component.ConfirmSheet
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.core.extensions.R
import com.altnautica.gcs.core.extensions.bridge.ConfirmBroker
import com.altnautica.gcs.core.extensions.bridge.ConfirmPrompt
import com.altnautica.gcs.core.extensions.data.ExtensionRepository
import com.altnautica.gcs.core.extensions.data.ExtensionsState
import com.altnautica.gcs.core.extensions.host.SlotMount
import com.altnautica.gcs.core.extensions.host.SlotMounts
import com.altnautica.gcs.core.extensions.manifest.ExtensionSlots
import com.altnautica.gcs.core.extensions.skills.ExtensionCockpitFocus
import com.altnautica.gcs.core.model.DeviceId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

@HiltViewModel
class ExtensionSlotViewModel @Inject constructor(
    private val repository: ExtensionRepository,
    private val confirm: ConfirmBroker,
) : ViewModel() {

    val prompts: StateFlow<List<ConfirmPrompt>> = confirm.pending

    fun mounts(node: DeviceId, slot: String, zone: String?): Flow<List<SlotMount>> =
        repository.extensions(node).map { s ->
            val exts = (s as? ExtensionsState.Loaded)?.extensions.orEmpty()
            SlotMounts.resolve(exts, slot, repository.profile(node), zone)
        }

    fun ensureLoaded(node: DeviceId) {
        if (repository.extensions(node).value is ExtensionsState.Loaded) return
        viewModelScope.launch { repository.refresh(node) }
    }

    fun answer(id: String, approved: Boolean) = confirm.answer(id, approved)

    private val owner = java.util.UUID.randomUUID().toString()

    fun claim(id: String): Boolean = confirm.claim(id, owner)

    /** Drop this host's claims so another host can show the prompt. */
    fun releaseClaims() = confirm.release(owner)

    override fun onCleared() {
        releaseClaims()
    }
}

@HiltViewModel
class ExtensionCockpitViewModel @Inject constructor(private val focus: ExtensionCockpitFocus) : ViewModel() {
    fun attach(node: DeviceId): () -> Unit = focus.attach(node)
}

/**
 * Focus the cockpit's node for extension skills and their state poll while the cockpit is
 * started (visible). Backgrounding the app or turning the screen off releases the focus,
 * which stops the poll. Call once inside the cockpit route.
 */
@Composable
fun ExtensionCockpitEffect(node: DeviceId) {
    val vm: ExtensionCockpitViewModel = hiltViewModel()
    ExtensionCockpitEffect(node, vm::attach)
}

@Composable
internal fun ExtensionCockpitEffect(node: DeviceId, attach: (DeviceId) -> () -> Unit) {
    val currentAttach by rememberUpdatedState(attach)
    LifecycleStartEffect(node) {
        val release = currentAttach(node)
        onStopOrDispose { release() }
    }
}

/**
 * Mounts every extension contribution for [slot] on [node]: `video.overlay`,
 * `cockpit.panel`, `cockpit.widget` (pass `hostProps["zone"]`), `map.overlay`,
 * `node.detail.tab`, `node.agent.page` or `node.surface`. A slot is mounted only when the
 * node granted the extension that slot's capability; nothing renders when no extension
 * contributes. Extension command approvals raised from these frames show as a hold
 * confirm sheet.
 */
@Composable
fun ExtensionSlotHost(
    node: DeviceId,
    slot: String,
    modifier: Modifier = Modifier,
    hostProps: Map<String, Any?> = emptyMap(),
) {
    val vm: ExtensionSlotViewModel = hiltViewModel(key = "extension-slot:${node.value}:$slot")
    LaunchedEffect(vm, node) { vm.ensureLoaded(node) }
    val zone = hostProps["zone"] as? String
    val mountsFlow = remember(vm, node, slot, zone) { vm.mounts(node, slot, zone) }
    val mounts by mountsFlow.collectAsStateWithLifecycle(emptyList())
    val overlay = slot == ExtensionSlots.VIDEO_OVERLAY || slot == ExtensionSlots.MAP_OVERLAY ||
        slot == ExtensionSlots.COCKPIT_WIDGET
    val hostEvent = remember(hostProps, slot) {
        if (slot == ExtensionSlots.VIDEO_OVERLAY) "video.overlay.props" to toJson(hostProps) else null
    }

    if (overlay) {
        Box(modifier) {
            for (m in mounts) {
                if (m.inline) continue
                ExtensionFrame(
                    node = node,
                    pluginId = m.pluginId,
                    mountKey = m.key,
                    modifier = Modifier.fillMaxSize(),
                    hostEvent = hostEvent,
                    quiet = true,
                )
            }
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (m in mounts) {
                ExtensionFrame(
                    node = node,
                    pluginId = m.pluginId,
                    mountKey = m.key,
                    modifier = Modifier.fillMaxWidth().height(PANEL_HEIGHT),
                )
            }
        }
    }
    ExtensionPromptHost(vm)
}

private val PANEL_HEIGHT = 320.dp

@Composable
internal fun ExtensionPromptHost(vm: ExtensionSlotViewModel) {
    ExtensionPromptHost(vm.prompts, vm::claim, vm::releaseClaims, vm::answer)
}

/**
 * Shows the head approval prompt when this host owns it. Claims are released when the
 * host leaves composition, so an unanswered prompt moves to a host still on screen.
 */
@Composable
internal fun ExtensionPromptHost(
    prompts: StateFlow<List<ConfirmPrompt>>,
    claim: (String) -> Boolean,
    releaseClaims: () -> Unit,
    answer: (String, Boolean) -> Unit,
) {
    val currentRelease by rememberUpdatedState(releaseClaims)
    DisposableEffect(Unit) {
        onDispose { currentRelease() }
    }
    val list by prompts.collectAsStateWithLifecycle()
    val head = list.firstOrNull() ?: return
    if (!claim(head.id)) return
    ConfirmSheet(
        title = head.title,
        message = head.body,
        onDismiss = { answer(head.id, false) },
        danger = head.critical,
    ) {
        HoldToConfirmButton(
            label = stringResource(R.string.extensions_confirm_hold),
            holdMs = 800,
            onConfirmed = { answer(head.id, true) },
            modifier = Modifier.fillMaxWidth(),
            danger = head.critical,
        )
        TextButton(onClick = { answer(head.id, false) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.extensions_confirm_decline))
        }
    }
}

/** Host props → JSON (numbers, strings, booleans, nested maps and lists). */
internal fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
    is Iterable<*> -> kotlinx.serialization.json.JsonArray(value.map(::toJson))
    else -> JsonPrimitive(value.toString())
}
