package com.altnautica.gcs.core.extensions.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.extensions.NODE
import com.altnautica.gcs.core.extensions.bridge.ConfirmBroker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ExtensionHostEffectsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun promptClaimIsReleasedWhenTheHostLeavesComposition() {
        val broker = ConfirmBroker()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch { broker.request("com.example.ext", "Extension command", "land", critical = true) }
        val id = broker.pending.value.single().id
        var shown by mutableStateOf(true)
        compose.setContent {
            AdosTheme {
                if (shown) {
                    ExtensionPromptHost(
                        prompts = broker.pending,
                        claim = { broker.claim(it, "a") },
                        releaseClaims = { broker.release("a") },
                        answer = broker::answer,
                    )
                }
            }
        }
        compose.waitForIdle()
        assertFalse("host a holds the prompt", broker.claim(id, "b"))

        shown = false
        compose.waitForIdle()
        assertTrue("another host can show the unanswered prompt", broker.claim(id, "b"))
        scope.cancel()
    }

    @Test
    fun cockpitFocusFollowsTheLifecycleNotComposition() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        var attached = 0
        var released = 0
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ExtensionCockpitEffect(NODE) { attached++; { released++ } }
            }
        }
        compose.waitForIdle()
        assertEquals(1, attached)

        // Backgrounded with the cockpit still composed: the focus (and its poll) is released.
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        assertEquals(1, released)

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(2, attached)
    }
}
