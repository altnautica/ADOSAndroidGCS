package com.altnautica.gcs.core.skills.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.altnautica.gcs.core.skills.MonotonicClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface InputEvent {
    /** A standard-layout gamepad button changed; [heldMs] is the press duration on release, 0 on press. */
    data class Button(val button: Int, val pressed: Boolean, val heldMs: Long) : InputEvent

    /** A hardware keyboard chord (see [canonicalChord]). Emitted on key down only. */
    data class Chord(val chord: String) : InputEvent

    /** Enter or numpad Enter, forwarded only while a confirm surface captures it. */
    data class Enter(val pressed: Boolean) : InputEvent
}

/** The screen currently taking hardware input (the cockpit); decides what the router consumes. */
interface InputCapture {
    /** True when the screen handles [chord]; the key event is then consumed. */
    fun consumesChord(chord: String): Boolean

    /** True while a confirm surface uses Enter as its hold source. */
    val capturesEnter: Boolean
}

/**
 * Activity-level input router. Gamepad sticks are dead-zoned with each axis's
 * reported flat range, every batched historical sample of a motion event is
 * applied in order, buttons carry hold timing, and hardware keyboard keys become
 * canonical chords. Events are consumed only while an [InputCapture] is set.
 */
@Singleton
class AdosInputRouter @Inject constructor(
    @ApplicationContext context: Context,
    private val clock: MonotonicClock,
) : InputRouter {
    private val _axes = MutableStateFlow(GamepadAxes.Neutral)
    private val _buttons = MutableStateFlow(0)
    private val _gamepadConnected = MutableStateFlow(false)
    private val _events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val pressedAt = LongArray(GamepadButton.HOME + 1)

    /** Current stick positions after the dead-zone. */
    val axes: StateFlow<GamepadAxes> = _axes.asStateFlow()

    /** Bitmask of pressed standard-layout buttons (bit n = button n). */
    val buttons: StateFlow<Int> = _buttons.asStateFlow()

    /** True while at least one gamepad or joystick is attached. */
    val gamepadConnected: StateFlow<Boolean> = _gamepadConnected.asStateFlow()

    val events: SharedFlow<InputEvent> = _events.asSharedFlow()

    @Volatile
    var capture: InputCapture? = null

    private val inputManager = context.getSystemService(InputManager::class.java)

    init {
        inputManager?.registerInputDeviceListener(
            object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) = refreshDevices()
                override fun onInputDeviceRemoved(deviceId: Int) = refreshDevices()
                override fun onInputDeviceChanged(deviceId: Int) = refreshDevices()
            },
            Handler(Looper.getMainLooper()),
        )
        refreshDevices()
    }

    private fun refreshDevices() {
        val connected = InputDevice.getDeviceIds().any { id -> InputDevice.getDevice(id)?.isGamepad() == true }
        _gamepadConnected.value = connected
        if (!connected) {
            _axes.value = GamepadAxes.Neutral
            releaseAll()
        }
    }

    private fun InputDevice.isGamepad(): Boolean =
        !isVirtual && (supportsSource(InputDevice.SOURCE_GAMEPAD) || supportsSource(InputDevice.SOURCE_JOYSTICK))

    override fun onMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val device = event.device
        for (i in 0 until event.historySize) applySample(event, device, i)
        applySample(event, device, -1)
        return capture != null
    }

    /** [pos] < 0 reads the current sample, otherwise a batched historical one. */
    private fun applySample(event: MotionEvent, device: InputDevice?, pos: Int) {
        fun read(axis: Int): Float {
            val raw = if (pos < 0) event.getAxisValue(axis) else event.getHistoricalAxisValue(axis, pos)
            val flat = device?.getMotionRange(axis, event.source)?.flat ?: 0f
            return applyDeadZone(raw, flat)
        }
        _axes.value = GamepadAxes(
            leftX = read(MotionEvent.AXIS_X),
            leftY = -read(MotionEvent.AXIS_Y),
            rightX = read(MotionEvent.AXIS_Z),
            rightY = -read(MotionEvent.AXIS_RZ),
        )
        // Controllers that report the D-pad as a hat axis instead of key events.
        val hatX = if (pos < 0) event.getAxisValue(MotionEvent.AXIS_HAT_X) else event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_X, pos)
        val hatY = if (pos < 0) event.getAxisValue(MotionEvent.AXIS_HAT_Y) else event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_Y, pos)
        setButton(GamepadButton.DPAD_LEFT, hatX < -0.5f)
        setButton(GamepadButton.DPAD_RIGHT, hatX > 0.5f)
        setButton(GamepadButton.DPAD_UP, hatY < -0.5f)
        setButton(GamepadButton.DPAD_DOWN, hatY > 0.5f)
    }

    override fun onKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val button = GamepadButton.fromKeyCode(event.keyCode)
        val fromGamepad = event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        if (button != null && fromGamepad) {
            if (event.repeatCount == 0) setButton(button, down)
            return capture != null
        }
        val cap = capture ?: return false
        if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!cap.capturesEnter) return false
            if (event.repeatCount == 0) _events.tryEmit(InputEvent.Enter(down))
            return true
        }
        val chord = canonicalChord(event.keyCode, event.metaState, event.getUnicodeChar(event.metaState)) ?: return false
        if (!cap.consumesChord(chord)) return false
        if (down && event.repeatCount == 0) _events.tryEmit(InputEvent.Chord(chord))
        return true
    }

    private fun setButton(button: Int, pressed: Boolean) {
        val bit = 1 shl button
        val was = _buttons.value and bit != 0
        if (was == pressed) return
        _buttons.value = if (pressed) _buttons.value or bit else _buttons.value and bit.inv()
        val now = clock.nowMs()
        val held = if (pressed) {
            pressedAt[button] = now
            0L
        } else {
            now - pressedAt[button]
        }
        _events.tryEmit(InputEvent.Button(button, pressed, held))
    }

    private fun releaseAll() {
        for (b in 0..GamepadButton.HOME) setButton(b, false)
    }
}
