package com.altnautica.gcs.feature.cockpit

import androidx.compose.runtime.Composable
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * System back closes an open cockpit sheet. Cockpit sheets are drawn in the
 * activity window (not dialog windows) so gamepad and keyboard input keep reaching
 * the input router and the system bars stay hidden; this handler, registered after
 * the cockpit's own, takes back first while the sheet shows.
 */
@Composable
internal fun SheetBackHandler(onDismiss: () -> Unit) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = true,
        onBackCompleted = onDismiss,
    )
}
