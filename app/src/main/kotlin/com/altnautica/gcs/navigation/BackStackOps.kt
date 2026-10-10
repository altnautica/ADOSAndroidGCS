package com.altnautica.gcs.navigation

import androidx.navigation3.runtime.NavKey

/** Back-stack moves; Fleet stays at the root so back from any top-level destination returns there. */
object BackStackOps {

    fun selectTopLevel(stack: MutableList<NavKey>, key: TopLevelKey) {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        if (stack.isEmpty()) stack.add(FleetKey)
        if (stack[0] != FleetKey) stack[0] = FleetKey
        if (key != FleetKey) stack.add(key)
    }

    /** Pushes [key] unless it is already on top. */
    fun push(stack: MutableList<NavKey>, key: NavKey) {
        if (stack.lastOrNull() != key) stack.add(key)
    }

    /** Replaces the top entry (e.g. Connect → the node it connected). */
    fun replaceTop(stack: MutableList<NavKey>, key: NavKey) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        push(stack, key)
    }

    fun pop(stack: MutableList<NavKey>) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** The top-level destination the navigation suite highlights. */
    fun currentTopLevel(stack: List<NavKey>): TopLevelKey = stack.lastOrNull { it is TopLevelKey } as? TopLevelKey ?: FleetKey

    /** The suite shows only while a top-level destination is on top; full routes hide it. */
    fun showsSuite(stack: List<NavKey>): Boolean = stack.lastOrNull() is TopLevelKey
}
