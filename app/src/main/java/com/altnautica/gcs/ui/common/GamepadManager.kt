package com.altnautica.gcs.ui.common

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Gamepad state with stick positions normalized to -1000..1000 range
 * and button bitmask.
 *
 * Mode 2 mapping (default):
 *   Left stick Y  = throttle (z)
 *   Left stick X  = yaw (r)
 *   Right stick Y = pitch (x)
 *   Right stick X = roll (y)
 */
data class GamepadState(
    val connected: Boolean = false,
    val deviceName: String = "",
    val x: Int = 0, // pitch: -1000 (nose down) to 1000 (nose up)
    val y: Int = 0, // roll: -1000 (left) to 1000 (right)
    val z: Int = 0, // throttle: -1000 (min) to 1000 (max)
    val r: Int = 0, // yaw: -1000 (left) to 1000 (right)
    val buttons: Int = 0,
) {
    val hasInput: Boolean get() = x != 0 || y != 0 || z != 0 || r != 0 || buttons != 0
}

@Singleton
class GamepadManager @Inject constructor() {

    companion object {
        private const val DEAD_ZONE = 0.05f
        private const val SCALE = 1000

        // Button bitmask positions (matching MAVLink MANUAL_CONTROL button encoding)
        const val BUTTON_A = 1 shl 0
        const val BUTTON_B = 1 shl 1
        const val BUTTON_X = 1 shl 2
        const val BUTTON_Y = 1 shl 3
        const val BUTTON_L1 = 1 shl 4
        const val BUTTON_R1 = 1 shl 5
        const val BUTTON_L2 = 1 shl 6
        const val BUTTON_R2 = 1 shl 7
    }

    private val _state = MutableStateFlow(GamepadState())
    val state: StateFlow<GamepadState> = _state.asStateFlow()

    /**
     * Check for connected gamepads and update connection state.
     * Call this periodically or on device connection events.
     */
    fun refreshConnection() {
        val deviceIds = InputDevice.getDeviceIds()
        val gamepad = deviceIds
            .mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull { device ->
                val sources = device.sources
                (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            }

        if (gamepad != null) {
            if (!_state.value.connected || _state.value.deviceName != gamepad.name) {
                _state.value = _state.value.copy(
                    connected = true,
                    deviceName = gamepad.name ?: "Gamepad",
                )
            }
        } else {
            if (_state.value.connected) {
                _state.value = GamepadState(connected = false)
            }
        }
    }

    /**
     * Process a MotionEvent from Activity.onGenericMotionEvent().
     * Reads stick axes and applies dead zone + Mode 2 mapping.
     */
    fun processMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK &&
            event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD
        ) {
            return false
        }

        // Read raw axes
        val leftX = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_X))
        val leftY = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_Y))
        val rightX = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_Z))
        val rightY = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_RZ))

        // Mode 2 mapping:
        // Left stick Y  -> throttle (z), inverted (push up = positive)
        // Left stick X  -> yaw (r)
        // Right stick Y -> pitch (x), inverted (push up = positive)
        // Right stick X -> roll (y)
        _state.value = _state.value.copy(
            x = (-rightY * SCALE).toInt().coerceIn(-SCALE, SCALE), // pitch
            y = (rightX * SCALE).toInt().coerceIn(-SCALE, SCALE),  // roll
            z = (-leftY * SCALE).toInt().coerceIn(-SCALE, SCALE),  // throttle
            r = (leftX * SCALE).toInt().coerceIn(-SCALE, SCALE),   // yaw
        )

        return true
    }

    /**
     * Process a KeyEvent from Activity.onKeyDown()/onKeyUp().
     * Tracks button presses as a bitmask.
     */
    fun processKeyEvent(event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD) {
            return false
        }

        val bit = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> BUTTON_A
            KeyEvent.KEYCODE_BUTTON_B -> BUTTON_B
            KeyEvent.KEYCODE_BUTTON_X -> BUTTON_X
            KeyEvent.KEYCODE_BUTTON_Y -> BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_L1 -> BUTTON_L1
            KeyEvent.KEYCODE_BUTTON_R1 -> BUTTON_R1
            KeyEvent.KEYCODE_BUTTON_L2 -> BUTTON_L2
            KeyEvent.KEYCODE_BUTTON_R2 -> BUTTON_R2
            else -> return false
        }

        val currentButtons = _state.value.buttons
        val newButtons = when (event.action) {
            KeyEvent.ACTION_DOWN -> currentButtons or bit
            KeyEvent.ACTION_UP -> currentButtons and bit.inv()
            else -> currentButtons
        }

        _state.value = _state.value.copy(buttons = newButtons)
        return true
    }

    private fun applyDeadZone(value: Float): Float {
        return if (abs(value) < DEAD_ZONE) 0f else value
    }
}
