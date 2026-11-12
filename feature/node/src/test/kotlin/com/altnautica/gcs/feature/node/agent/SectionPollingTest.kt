package com.altnautica.gcs.feature.node.agent

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SectionPollingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sectionReadoutsStopWhileTheAppIsInTheBackground() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        val section = AgentSection.ALL.first()
        val events = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                SectionPolling(section, open = { events += "open ${it.id}" }, close = { events += "close" })
            }
        }
        compose.waitForIdle()
        assertEquals(listOf("open ${section.id}"), events)

        // Backgrounded with the section still on screen: its polls stop.
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        assertEquals(listOf("open ${section.id}", "close"), events)

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(listOf("open ${section.id}", "close", "open ${section.id}"), events)
    }
}
