package com.altnautica.gcs.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackStackOpsTest {

    @Test
    fun topLevelSelectionKeepsFleetAtTheRoot() {
        val stack = mutableListOf<NavKey>(FleetKey, NodeKey("a"), CockpitKey("a"))
        BackStackOps.selectTopLevel(stack, PlanKey)
        assertEquals(listOf(FleetKey, PlanKey), stack)
        BackStackOps.selectTopLevel(stack, FleetKey)
        assertEquals(listOf<NavKey>(FleetKey), stack)
    }

    @Test
    fun suiteHidesOnFullRoutes() {
        val stack = mutableListOf<NavKey>(FleetKey, SettingsKey)
        assertTrue(BackStackOps.showsSuite(stack))
        assertEquals(SettingsKey, BackStackOps.currentTopLevel(stack))
        BackStackOps.push(stack, NodeKey("a"))
        assertFalse(BackStackOps.showsSuite(stack))
        assertEquals(SettingsKey, BackStackOps.currentTopLevel(stack))
    }

    @Test
    fun connectIsReplacedByTheConnectedNode() {
        val stack = mutableListOf<NavKey>(FleetKey, ConnectKey())
        BackStackOps.replaceTop(stack, NodeKey("drone-1"))
        assertEquals(listOf(FleetKey, NodeKey("drone-1")), stack)
    }

    @Test
    fun pushDedupesAndPopKeepsRoot() {
        val stack = mutableListOf<NavKey>(FleetKey)
        BackStackOps.push(stack, CockpitKey("a"))
        BackStackOps.push(stack, CockpitKey("a"))
        assertEquals(2, stack.size)
        BackStackOps.pop(stack)
        BackStackOps.pop(stack)
        assertEquals(listOf<NavKey>(FleetKey), stack)
    }
}
