package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role

/**
 * A [clickable] that a cancelled key release does not click. The controller bridge ends a
 * confirm press with a key-up flagged `FLAG_CANCELED` when a chord takes the button over
 * or the pad goes away; Compose's clickable clicks on any confirm key-up, so this one
 * notes the flag on its way down and drops that click. Touch clicks are unaffected.
 */
fun Modifier.padClickable(enabled: Boolean = true, role: Role? = null, onClick: () -> Unit): Modifier = composed {
    val canceled = remember { BooleanArray(1) }
    this
        .onPreviewKeyEvent { e ->
            canceled[0] = e.type == KeyEventType.KeyUp && e.nativeKeyEvent.isCanceled
            false
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    canceled[0] = false
                }
            }
        }
        .clickable(enabled = enabled, role = role) {
            if (canceled[0]) canceled[0] = false else onClick()
        }
}
