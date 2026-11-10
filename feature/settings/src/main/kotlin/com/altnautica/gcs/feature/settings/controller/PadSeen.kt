package com.altnautica.gcs.feature.settings.controller

import com.altnautica.gcs.core.skills.input.AdosInputRouter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

/** The line the controls screen shows while no controller drives input. */
enum class PadPrompt {
    /** A controller drives input: no prompt. */
    None,

    /** A gamepad was attached this session: ask for a button press to pick it up. */
    PressAnyButton,

    /** No gamepad was attached this session: say so instead of asking for a press. */
    NoPad,
}

/**
 * The prompt for the controls screen. "Press any button" only makes sense once a
 * gamepad or joystick has been attached at some point in this process ([padSeen]);
 * before that the screen says there is no pad.
 */
internal fun padPromptFor(controllerActive: Boolean, padSeen: Boolean): PadPrompt = when {
    controllerActive -> PadPrompt.None
    padSeen -> PadPrompt.PressAnyButton
    else -> PadPrompt.NoPad
}

/**
 * Remembers, for the life of the process, whether a gamepad or joystick was ever attached.
 * It watches the router from its own scope from the moment it is created, so a pad that
 * attaches and detaches while no screen collects [seen] is still remembered.
 */
@Singleton
class PadSeenMemory internal constructor(router: AdosInputRouter, scope: CoroutineScope) {
    // Unconfined: each attach is latched in the thread that reports it, before it can be overwritten.
    @Inject constructor(router: AdosInputRouter) : this(router, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    /** True once a gamepad or joystick was attached at any point after this was created. */
    val seen: StateFlow<Boolean> = router.gamepadConnected
        .runningFold(false) { seen, connected -> seen || connected }
        .stateIn(scope, SharingStarted.Eagerly, router.gamepadConnected.value)
}
