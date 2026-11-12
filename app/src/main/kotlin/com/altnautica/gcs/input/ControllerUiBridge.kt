package com.altnautica.gcs.input

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommand
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommandDispatcher
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.NavDirection
import com.altnautica.gcs.core.skills.input.UiCommand
import com.altnautica.gcs.core.skills.input.UiInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Turns controller [UiInput] into ordinary UI input for the activity while it is started:
 * confirm becomes a centre-key press (so clickables and hold-to-confirm controls react),
 * back goes through the back dispatcher (which feeds the navigation event handlers),
 * held directions become repeating D-pad keys, and commands go to the console
 * command handlers. Synthesized keys carry a keyboard source, so the input router
 * passes them through instead of consuming them again.
 */
class ControllerUiBridge(
    private val activity: ComponentActivity,
    private val router: AdosInputRouter,
    private val commands: ConsoleCommandDispatcher,
) {
    private var repeatJob: Job? = null
    private var activateDownTime = 0L

    fun start() {
        activity.lifecycleScope.launch(Dispatchers.Main.immediate) {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                coroutineScope {
                    try {
                        router.uiInput.collect { handle(this, it) }
                    } finally {
                        repeatJob?.cancel()
                        repeatJob = null
                    }
                }
            }
        }
    }

    private fun handle(scope: CoroutineScope, input: UiInput) {
        when (input) {
            is UiInput.Activate -> {
                leaveTouchMode()
                val now = SystemClock.uptimeMillis()
                if (input.pressed) activateDownTime = now
                // A chord start or a pad loss ends the press as cancelled: the key-up
                // carries FLAG_CANCELED so the focused control does not click.
                sendKey(if (input.pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, activateDownTime, now, input.keyFlags)
            }
            UiInput.Back -> activity.onBackPressedDispatcher.onBackPressed()
            is UiInput.Direction -> {
                repeatJob?.cancel()
                repeatJob = input.dir?.let { dir -> scope.launch { repeatDirection(dir) } }
            }
            is UiInput.Command -> commands.dispatch(input.command.toConsole())
        }
    }

    private suspend fun repeatDirection(dir: NavDirection) {
        leaveTouchMode()
        val code = when (dir) {
            NavDirection.Up -> KeyEvent.KEYCODE_DPAD_UP
            NavDirection.Down -> KeyEvent.KEYCODE_DPAD_DOWN
            NavDirection.Left -> KeyEvent.KEYCODE_DPAD_LEFT
            NavDirection.Right -> KeyEvent.KEYCODE_DPAD_RIGHT
        }
        tap(code)
        delay(REPEAT_DELAY_MS)
        while (true) {
            tap(code)
            delay(REPEAT_INTERVAL_MS)
        }
    }

    private fun tap(code: Int) {
        val now = SystemClock.uptimeMillis()
        sendKey(KeyEvent.ACTION_DOWN, code, now, now)
        sendKey(KeyEvent.ACTION_UP, code, now, now)
    }

    // Activity.dispatchKeyEvent is public platform API; the restriction marker
    // sits on the androidx.core ComponentActivity override in between.
    @SuppressLint("RestrictedApi")
    private fun sendKey(action: Int, code: Int, downTime: Long, eventTime: Long, flags: Int = 0) {
        activity.dispatchKeyEvent(
            KeyEvent(downTime, eventTime, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD),
        )
    }

    /**
     * Keys dispatched straight to the activity skip the window's touch-mode exit, and
     * clickables are not focusable in touch mode; leave it the way a hardware key would.
     */
    private fun leaveTouchMode() {
        val decor = activity.window.decorView
        if (decor.isInTouchMode) (decor.findFocus() ?: decor).requestFocusFromTouch()
    }

    private fun UiCommand.toConsole(): ConsoleCommand = when (this) {
        UiCommand.Details -> ConsoleCommand.Details
        UiCommand.More -> ConsoleCommand.More
        UiCommand.MoreDown -> ConsoleCommand.MoreDown
        UiCommand.MoreUp -> ConsoleCommand.MoreUp
        UiCommand.PrevTab -> ConsoleCommand.PrevTab
        UiCommand.NextTab -> ConsoleCommand.NextTab
        UiCommand.PrevPage -> ConsoleCommand.PrevPage
        UiCommand.NextPage -> ConsoleCommand.NextPage
        UiCommand.Menu -> ConsoleCommand.Menu
        UiCommand.QuickMenu -> ConsoleCommand.QuickMenu
        UiCommand.Home -> ConsoleCommand.Home
        UiCommand.Capture -> ConsoleCommand.Capture
    }

    private companion object {
        const val REPEAT_DELAY_MS = 350L
        const val REPEAT_INTERVAL_MS = 110L
    }
}
