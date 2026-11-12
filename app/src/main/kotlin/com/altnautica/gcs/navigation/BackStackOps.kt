package com.altnautica.gcs.navigation

import androidx.navigation3.runtime.NavKey

/** Back-stack moves; Home stays at the root, so back from any surface ends there. */
object BackStackOps {

    /** Pushes [key] unless it is already on top. */
    fun push(stack: MutableList<NavKey>, key: NavKey) {
        if (stack.lastOrNull() != key) stack.add(key)
    }

    /** Replaces the top entry, keeping the root. */
    fun replaceTop(stack: MutableList<NavKey>, key: NavKey) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        push(stack, key)
    }

    fun pop(stack: MutableList<NavKey>) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Removes everything above the root. */
    fun goHome(stack: MutableList<NavKey>) {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /**
     * Leaves the Connect surface after a pairing. With a flight screen anywhere on the
     * stack only the surfaces above it close, so a pair link never drops a cockpit;
     * otherwise everything closes back to Home.
     */
    fun afterConnect(stack: MutableList<NavKey>) {
        val cockpit = stack.indexOfLast { it is CockpitKey }
        if (cockpit < 0) goHome(stack) else while (stack.size > cockpit + 1) stack.removeAt(stack.lastIndex)
    }

    /** Pops back to [key] when it is already on the stack, otherwise pushes it. */
    fun navigateTo(stack: MutableList<NavKey>, key: NavKey) {
        if (key in stack) {
            while (stack.last() != key) stack.removeAt(stack.lastIndex)
        } else {
            stack.add(key)
        }
    }
}
