package com.altnautica.gcs.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** Hold controls listening for a confirm press that ended cancelled; see [CancelledConfirmGuard]. */
@Stable
class ConfirmCancel internal constructor() {
    private val listeners = mutableListOf<() -> Unit>()

    internal fun add(listener: () -> Unit) {
        listeners += listener
    }

    internal fun remove(listener: () -> Unit) {
        listeners -= listener
    }

    internal fun fire() = listeners.toList().forEach { it() }
}

/** The guard's [ConfirmCancel]; outside a guard nothing ever fires it. */
val LocalConfirmCancel = staticCompositionLocalOf { ConfirmCancel() }

/**
 * Drops every cancelled confirm-key release (Enter, centre, gamepad A, Space with
 * `FLAG_CANCELED`) before any control sees it. The controller bridge ends a held select
 * that way when a chord takes the button over or the pad goes away, and the system does
 * the same for a key cut short; Compose clickables would otherwise click on it, since they
 * ignore the flag. Hold controls still need to stop, so the guard tells the ones that
 * listen through [OnConfirmCancelled]. Wrap the whole app content once.
 */
@Composable
fun CancelledConfirmGuard(content: @Composable () -> Unit) {
    val cancel = remember { ConfirmCancel() }
    CompositionLocalProvider(LocalConfirmCancel provides cancel) {
        Box(
            Modifier.onPreviewKeyEvent { e ->
                val cancelled = e.type == KeyEventType.KeyUp && isConfirmKey(e.key) && e.nativeKeyEvent.isCanceled
                if (cancelled) cancel.fire()
                cancelled
            },
            propagateMinConstraints = true,
        ) { content() }
    }
}

/**
 * Runs [onCancel] when a confirm press ends cancelled anywhere under the guard. Hold
 * controls use it to stop their hold, the release they would have had from the key-up.
 */
@Composable
fun OnConfirmCancelled(onCancel: () -> Unit) {
    val cancel = LocalConfirmCancel.current
    val current by rememberUpdatedState(onCancel)
    DisposableEffect(cancel) {
        val listener = { current() }
        cancel.add(listener)
        onDispose { cancel.remove(listener) }
    }
}
