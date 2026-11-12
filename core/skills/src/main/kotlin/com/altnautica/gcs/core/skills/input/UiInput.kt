package com.altnautica.gcs.core.skills.input

import android.view.KeyEvent

/** A held navigation direction from the D-pad hat or the left stick. */
enum class NavDirection { Up, Down, Left, Right }

/** Screen-level controller commands; each screen decides what they do. */
enum class UiCommand { Details, More, MoreDown, MoreUp, PrevTab, NextTab, PrevPage, NextPage, Menu, QuickMenu, Home, Capture }

/** Controller input routed as UI navigation outside the cockpit's flight capture. */
sealed interface UiInput {
    /**
     * The confirm button (A) changed state; the app turns it into a centre-key press.
     * A [canceled] release ends a press that must not click: a chord took the button over,
     * or its pad went away while it was held.
     */
    data class Activate(val pressed: Boolean, val canceled: Boolean = false) : UiInput {
        /** Flags for the synthesized centre key: a cancelled release carries [KeyEvent.FLAG_CANCELED]. */
        val keyFlags: Int get() = if (canceled) KeyEvent.FLAG_CANCELED else 0
    }

    /** The back button (B) was pressed. */
    data object Back : UiInput

    /** The held navigation direction changed; null means released. */
    data class Direction(val dir: NavDirection?) : UiInput

    data class Command(val command: UiCommand) : UiInput
}
