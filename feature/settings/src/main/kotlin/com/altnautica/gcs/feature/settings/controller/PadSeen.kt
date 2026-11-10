package com.altnautica.gcs.feature.settings.controller

import com.altnautica.gcs.core.skills.input.AdosInputRouter
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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

/** Remembers, for the life of the process, whether a gamepad or joystick was ever attached. */
@Singleton
class PadSeenMemory @Inject constructor(router: AdosInputRouter) {
    private val latched = AtomicBoolean(false)

    /** True once a gamepad or joystick was attached at any point after this was created. */
    val seen: Flow<Boolean> = router.gamepadConnected
        .map { connected ->
            if (connected) latched.set(true)
            latched.get()
        }
        .distinctUntilChanged()
}
