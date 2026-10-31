package com.altnautica.gcs.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

class BackStackOpsTest {

    @Test
    fun goHomeKeepsOnlyTheRoot() {
        val stack = mutableListOf<NavKey>(HomeKey, NodeKey("a"), CockpitKey("a"))
        BackStackOps.goHome(stack)
        assertEquals(listOf<NavKey>(HomeKey), stack)
    }

    @Test
    fun goHomeAtTheRootIsANoOp() {
        val stack = mutableListOf<NavKey>(HomeKey)
        BackStackOps.goHome(stack)
        assertEquals(listOf<NavKey>(HomeKey), stack)
    }

    @Test
    fun navigateToPopsBackToAKeyOnTheStack() {
        val stack = mutableListOf<NavKey>(HomeKey, CockpitKey("a"), SettingsKey, LicencesKey)
        BackStackOps.navigateTo(stack, CockpitKey("a"))
        assertEquals(listOf(HomeKey, CockpitKey("a")), stack)
    }

    @Test
    fun navigateToPushesAKeyNotOnTheStack() {
        val stack = mutableListOf<NavKey>(HomeKey, CockpitKey("a"))
        BackStackOps.navigateTo(stack, PlanKey("a"))
        assertEquals(listOf(HomeKey, CockpitKey("a"), PlanKey("a")), stack)
        BackStackOps.navigateTo(stack, PlanKey(null))
        assertEquals(listOf(HomeKey, CockpitKey("a"), PlanKey("a"), PlanKey(null)), stack)
    }

    @Test
    fun replaceTopKeepsTheRoot() {
        val stack = mutableListOf<NavKey>(HomeKey, ConnectKey())
        BackStackOps.replaceTop(stack, NodeKey("drone-1"))
        assertEquals(listOf(HomeKey, NodeKey("drone-1")), stack)
    }

    @Test
    fun pushDedupesAndPopKeepsRoot() {
        val stack = mutableListOf<NavKey>(HomeKey)
        BackStackOps.push(stack, CockpitKey("a"))
        BackStackOps.push(stack, CockpitKey("a"))
        assertEquals(2, stack.size)
        BackStackOps.pop(stack)
        BackStackOps.pop(stack)
        assertEquals(listOf<NavKey>(HomeKey), stack)
    }
}
