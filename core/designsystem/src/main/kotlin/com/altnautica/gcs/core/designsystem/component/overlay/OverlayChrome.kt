package com.altnautica.gcs.core.designsystem.component.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.altnautica.gcs.core.designsystem.R

/** Full-window scrim behind an overlay; a tap (or the accessibility click) dismisses. */
@Composable
internal fun OverlayScrim(color: Color, onDismiss: () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    val label = stringResource(R.string.console_dismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(color)
            .pointerInput(Unit) { detectTapGestures { dismiss() } }
            .semantics {
                onClick(label = label) {
                    dismiss()
                    true
                }
            },
    )
}

/**
 * The overlay panel as one focus target that traps focus: D-pad focus can move
 * between the panel's children but never out to the screen underneath. [requester]
 * takes initial focus (see [OverlayInitialFocus]).
 */
internal fun Modifier.overlayFocusTrap(requester: FocusRequester): Modifier =
    focusRequester(requester)
        .focusProperties { onExit = { cancelFocusChange() } }
        .focusTarget()

/** Moves focus into the overlay panel, then onto its first focusable child. */
@Composable
internal fun OverlayInitialFocus(requester: FocusRequester) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(requester) {
        if (requester.requestFocus(FocusDirection.Enter)) focusManager.moveFocus(FocusDirection.Enter)
    }
}

/**
 * System back (and the controller back button) closes the overlay; the newest overlay
 * wins. Without a back dispatcher in the window (design previews) there is no back.
 */
@Composable
internal fun OverlayBackHandler(onBack: () -> Unit) {
    if (LocalNavigationEventDispatcherOwner.current == null) return
    val back by rememberUpdatedState(onBack)
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = true,
        onBackCompleted = { back() },
    )
}
