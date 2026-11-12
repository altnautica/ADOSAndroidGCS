package com.altnautica.gcs.feature.settings.controller

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.console.ConsoleButton
import com.altnautica.gcs.core.designsystem.component.console.ConsoleScaffold
import com.altnautica.gcs.core.designsystem.component.console.Hint
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.skills.controls.StickMode
import com.altnautica.gcs.core.skills.spec.ControlBindingSpec
import com.altnautica.gcs.feature.settings.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ControllerTab(val title: Int) {
    Controls(R.string.controls_tab_controls),
    Status(R.string.controls_tab_status),
}

/**
 * Settings › Controls: the controller drawing with live input and editable bindings,
 * calibration and hardware checks; then the controller's status, pairing guide and
 * preferences.
 */
@Composable
fun ControllerRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    controls: ControlsViewModel = hiltViewModel(),
    status: ControllerViewModel = hiltViewModel(),
) {
    val state by controls.state.collectAsStateWithLifecycle()
    val statusState by status.state.collectAsStateWithLifecycle()
    val input = controls.input.collectAsStateWithLifecycle()
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tab = ControllerTab.entries[tabIndex]
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val back by rememberUpdatedState(onBack)

    LaunchedEffect(controls) {
        controls.messages.collect { m ->
            val text = when (m) {
                ControlsMessage.Locked -> resources.getString(R.string.controls_message_locked)
                ControlsMessage.SameControl -> resources.getString(R.string.controls_message_same_control)
                ControlsMessage.Saved -> resources.getString(R.string.controls_message_saved)
                ControlsMessage.SaveRefused -> resources.getString(R.string.controls_message_save_refused)
                ControlsMessage.Imported -> resources.getString(R.string.controls_message_imported)
                ControlsMessage.ImportUnreadable -> resources.getString(R.string.controls_message_import_unreadable)
                is ControlsMessage.ImportMissingLocked ->
                    controls.spec.setupStrings.format("controls.import.missing_locked", mapOf("actions" to m.actions.joinToString(", ")))
                ControlsMessage.ImportRefused -> resources.getString(R.string.controls_message_import_refused)
                ControlsMessage.FileFailed -> resources.getString(R.string.controls_message_file_failed)
            }
            snackbar.showSnackbar(text)
        }
    }
    // "Press a control" and the stick checks hold the controller: release them with the screen.
    DisposableEffect(controls) {
        onDispose {
            controls.endPress()
            controls.stopTest()
            controls.closeCalibration()
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_MIME)) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val text = controls.exportText()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            }.isSuccess
            if (!ok) controls.fileFailed()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
            }.getOrNull()
            if (text == null) controls.fileFailed() else controls.importText(text)
        }
    }

    val actions = remember(controls) {
        object : ControlsActions {
            override fun openControl(control: String) = controls.openControl(control)
            override fun closeControl() = controls.closeControl()
            override fun setAction(row: ControlBindingSpec, action: String) = controls.setAction(row, action)
            override fun addBinding(control: String, gesture: String, action: String) = controls.addBinding(control, gesture, action)
            override fun save() = controls.save()
            override fun discard() = controls.discard()
            override fun resetToDefault() = controls.resetToDefault()
            override fun setStickMode(mode: StickMode) = controls.setStickMode(mode)
            override fun setDeadzone(value: Float) = controls.setDeadzone(value)
            override fun startPress() = controls.startPress()
            override fun endPress() = controls.endPress()
            override fun pressedOnScreen(control: String) = controls.pressedOnScreen(control)
            override fun startCalibration() = controls.startCalibration()
            override fun closeCalibration() = controls.closeCalibration()
            override fun clearCalibration() = controls.clearCalibration()
            override fun testRumble() = controls.testRumble()
            override fun testLightbar() = controls.testLightbar()
            override fun toggleStickTest() = controls.toggleStickTest()
            override fun exportFile() = exporter.launch(EXPORT_NAME)
            override fun importFile() = importer.launch(arrayOf(EXPORT_MIME, "text/plain", "*/*"))
            override fun nextTourStep() = controls.nextTourStep()
            override fun finishTour() = controls.finishTour()
            override fun setOnScreenSticks(on: Boolean) = controls.setOnScreenSticks(on)
        }
    }

    Box(modifier.fillMaxSize()) {
        ConsoleScaffold(
            title = controls.spec.setupStrings.format("controls.title"),
            onBack = onBack,
            tabs = ControllerTab.entries.map { stringResource(it.title) },
            selectedTab = tabIndex,
            onSelectTab = { tabIndex = it },
            hints = listOf(
                Hint(ConsoleButton.Confirm, stringResource(R.string.settings_hint_select)),
                Hint(ConsoleButton.Back, stringResource(R.string.settings_nav_back)),
                Hint(ConsoleButton.PrevTab, ""),
                Hint(ConsoleButton.NextTab, stringResource(R.string.settings_controller_hint_tabs)),
            ),
        ) { padding ->
            when (tab) {
                ControllerTab.Controls -> ControlsScreen(
                    state = state,
                    input = input,
                    spec = controls.spec,
                    actions = actions,
                    actionLabel = controls::actionLabel,
                    modifier = Modifier.padding(padding),
                )
                ControllerTab.Status -> ControllerStatusTab(
                    state = statusState,
                    actions = StatusActions(
                        onRumble = status::setRumble,
                        onLightbar = status::setLightbar,
                        onExpo = status::setExpo,
                        onRemoveAllModes = status::removeAllModes,
                    ),
                    padding = padding,
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = AdosSpacing.S10))
    }
}

private const val EXPORT_MIME = "application/json"
private const val EXPORT_NAME = "ados-controls.json"
