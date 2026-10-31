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

/**
 * Activity-level input router. Gamepad sticks are dead-zoned with each axis's
 * reported flat range plus the operator dead zone, every batched historical sample
 * of a motion event is applied in order, buttons carry hold timing, and hardware
 * keyboard keys become canonical chords.
 *
 * Gamepad buttons are always consumed, so the platform never turns one into a
 * fallback key (space, centre, back, home). While an [InputCapture] takes flight
 * input they arrive as [InputEvent.Button]; otherwise they also become [UiInput]
 * navigation. The button bitmask updates in both modes.
 */
@Singleton
class AdosInputRouter @Inject constructor(
    @ApplicationContext context: Context,
    private val clock: MonotonicClock,
) : InputRouter {
    private val _axes = MutableStateFlow(GamepadAxes.Neutral)
    private val _buttons = MutableStateFlow(0)
    private val _gamepadConnected = MutableStateFlow(false)
    private val _activeDeviceId = MutableStateFlow<Int?>(null)
    private val _lastInputController = MutableStateFlow(false)
    private val _events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _uiInput = MutableSharedFlow<UiInput>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val pressedAt = LongArray(GamepadButton.HOME + 1)
    private val stickNav = StickNavigator()
    private var navDirection: NavDirection? = null
    private var enterHeld = false

    /** Current stick positions after the dead-zone. */
    val axes: StateFlow<GamepadAxes> = _axes.asStateFlow()

    /** Bitmask of pressed standard-layout buttons (bit n = button n). */
    val buttons: StateFlow<Int> = _buttons.asStateFlow()

    /** True while at least one gamepad or joystick is attached. */
    val gamepadConnected: StateFlow<Boolean> = _gamepadConnected.asStateFlow()

    /**
     * The gamepad whose sticks and buttons drive input. The first gamepad event sets it;
     * a button press on another gamepad takes over; it clears when that device is removed.
     */
    val activeDeviceId: StateFlow<Int?> = _activeDeviceId.asStateFlow()

    /** True after a key or motion from a gamepad or joystick; false after a touch. */
    val lastInputController: StateFlow<Boolean> = _lastInputController.asStateFlow()

    val events: SharedFlow<InputEvent> = _events.asSharedFlow()

    /** Controller UI navigation, emitted outside flight capture (and for app commands inside it). */
    val uiInput: SharedFlow<UiInput> = _uiInput.asSharedFlow()

    /** Operator dead zone (0..0.2) added to each axis's hardware flat range. */
    @Volatile
    var extraDeadZone: Float = 0f

    @Volatile
    var capture: InputCapture? = null
        set(value) {
            field = value
            // A held navigation direction must not keep repeating once flight input takes over.
            if (value != null && !value.uiNavigation) setDirection(null)
        }

    /**
     * While true the left stick no longer moves UI focus; a screen that reads [axes]
     * directly (the planner map cursor) sets it while it has focus. The hat still navigates.
     */
    @Volatile
    var suppressStickNavigation: Boolean = false
        set(value) {
            field = value
            if (value) {
                stickNav.reset()
                setDirection(null)
            }
        }

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

    internal fun refreshDevices() {
        val ids = InputDevice.getDeviceIds()
        val connected = ids.any { id -> InputDevice.getDevice(id)?.isGamepad() == true }
        val active = _activeDeviceId.value
        if (active != null && active !in ids) {
            // The active pad is gone: centre everything even if another pad remains.
            neutralize()
            _activeDeviceId.value = null
        }
        _gamepadConnected.value = connected
        if (!connected) {
            _axes.value = GamepadAxes.Neutral
            releaseAll()
            setDirection(null)
        }
    }

    private fun InputDevice.isGamepad(): Boolean =
        !isVirtual && (supportsSource(InputDevice.SOURCE_GAMEPAD) || supportsSource(InputDevice.SOURCE_JOYSTICK))

    /** No capture, or a capture whose overlay navigates like the rest of the app. */
    private fun uiMode(): Boolean = capture.let { it == null || it.uiNavigation }

    override fun onTouch() {
        _lastInputController.value = false
    }

    override fun onMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        _lastInputController.value = true
        val device = event.device
        val active = _activeDeviceId.value
        if (active == null) {
            _activeDeviceId.value = event.deviceId
        } else if (active != event.deviceId) {
            // Motion from a non-active pad never moves the sticks.
            return true
        }
        for (i in 0 until event.historySize) applySample(event, device, i)
        applySample(event, device, -1)
        updateDirection(event)
        // Always consumed: the platform's joystick-to-D-pad synthesis would double-move focus.
        return true
    }

    /** [pos] < 0 reads the current sample, otherwise a batched historical one. */
    private fun applySample(event: MotionEvent, device: InputDevice?, pos: Int) {
        val extra = extraDeadZone
        fun read(axis: Int): Float {
            val raw = if (pos < 0) event.getAxisValue(axis) else event.getHistoricalAxisValue(axis, pos)
            val flat = device?.getMotionRange(axis, event.source)?.flat ?: 0f
            return applyDeadZone(raw, flat, extra)
        }
        fun trigger(primary: Int, fallback: Int): Float {
            val axis = if (device?.getMotionRange(primary, event.source) != null) primary else fallback
            val raw = if (pos < 0) event.getAxisValue(axis) else event.getHistoricalAxisValue(axis, pos)
            val flat = device?.getMotionRange(axis, event.source)?.flat ?: 0f
            return applyDeadZone(raw, flat, 0f).coerceIn(0f, 1f)
        }
        _axes.value = GamepadAxes(
            leftX = read(MotionEvent.AXIS_X),
            leftY = -read(MotionEvent.AXIS_Y),
            rightX = read(MotionEvent.AXIS_Z),
            rightY = -read(MotionEvent.AXIS_RZ),
            leftTrigger = trigger(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
            rightTrigger = trigger(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS),
        )
        // Controllers that report the D-pad as a hat axis instead of key events.
        val hatX = if (pos < 0) event.getAxisValue(MotionEvent.AXIS_HAT_X) else event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_X, pos)
        val hatY = if (pos < 0) event.getAxisValue(MotionEvent.AXIS_HAT_Y) else event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_Y, pos)
        setButton(GamepadButton.DPAD_LEFT, hatX < -0.5f)
        setButton(GamepadButton.DPAD_RIGHT, hatX > 0.5f)
        setButton(GamepadButton.DPAD_UP, hatY < -0.5f)
        setButton(GamepadButton.DPAD_DOWN, hatY > 0.5f)
    }

    /**
     * UI mode: the hat, and the left stick when nothing captures input, hold a navigation
     * direction. With a capture (a cockpit overlay) only the hat navigates; the sticks fly.
     */
    private fun updateDirection(event: MotionEvent) {
        val cap = capture
        if (cap != null && !cap.uiNavigation) {
            stickNav.reset()
            setDirection(null)
            return
        }
        val hat = hatDirection(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y))
        val stick = if (cap == null && !suppressStickNavigation) {
            _axes.value.let { stickNav.update(it.leftX, it.leftY) }
        } else {
            stickNav.reset()
            null
        }
        setDirection(hat ?: stick)
    }

    private fun setDirection(dir: NavDirection?) {
        if (dir == navDirection) return
        navDirection = dir
        _uiInput.tryEmit(UiInput.Direction(dir))
    }

    override fun onKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val code = event.keyCode
        val fromGamepad = event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        if (fromGamepad && (isGamepadButtonKey(code) || isDpadKey(code))) return onGamepadKey(event, down)
        if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            val wasHeld = enterHeld
            enterHeld = down
            val cap = capture ?: return false
            if (!cap.capturesEnter) return false
            if (down != wasHeld) _events.tryEmit(InputEvent.Enter(down))
            return true
        }
        val cap = capture ?: return false
        val chord = canonicalChord(code, event.metaState, event.getUnicodeChar(event.metaState)) ?: return false
        if (!cap.consumesChord(chord)) return false
        if (down && event.repeatCount == 0) _events.tryEmit(InputEvent.Chord(chord))
        return true
    }

    /**
     * Gamepad buttons and D-pad keys. Every button is consumed; a gamepad D-pad key is
     * left to the platform in UI mode so Compose moves focus with it.
     */
    private fun onGamepadKey(event: KeyEvent, down: Boolean): Boolean {
        _lastInputController.value = true
        val ui = uiMode()
        val passThrough = ui && isDpadKey(event.keyCode)
        val active = _activeDeviceId.value
        if (active == null) {
            _activeDeviceId.value = event.deviceId
        } else if (active != event.deviceId) {
            // Only a deliberate press lets a second pad take over.
            if (!down) return !passThrough
            neutralize()
            _activeDeviceId.value = event.deviceId
        }
        // L3, R3 and the numbered buttons outside the standard layout carry no index.
        val button = GamepadButton.fromKeyCode(event.keyCode) ?: return true
        // The window swallows the first down of a navigation key (Enter, D-pad) when it
        // leaves touch mode, so a repeated down that finds the key up starts the press.
        val held = setButton(button, down)
        if (held != null && !(down && event.repeatCount > 0)) {
            val inputs = if (ui) uiInputsForButton(button, down, held) else captureUiInputsForButton(button, down, held)
            inputs.forEach { _uiInput.tryEmit(it) }
        }
        return !passThrough
    }

    /** Updates the bitmask; returns the hold time (0 on press) when the state changed, else null. */
    private fun setButton(button: Int, pressed: Boolean): Long? {
        val bit = 1 shl button
        val was = _buttons.value and bit != 0
        if (was == pressed) return null
        _buttons.value = if (pressed) _buttons.value or bit else _buttons.value and bit.inv()
        val now = clock.nowMs()
        val held = if (pressed) {
            pressedAt[button] = now
            0L
        } else {
            now - pressedAt[button]
        }
        _events.tryEmit(InputEvent.Button(button, pressed, held))
        return held
    }

    /**
     * Centres the sticks and releases every held button. Called when the screen taking
     * input stops or its window loses focus: the activity then receives no further
     * joystick events, so the last deflection must not linger.
     */
    fun neutralize() {
        _axes.value = GamepadAxes.Neutral
        enterHeld = false
        releaseAll()
        stickNav.reset()
        setDirection(null)
    }

    private fun releaseAll() {
        // A held confirm press must not stay down in the UI once its pad is gone.
        if (_buttons.value and (1 shl GamepadButton.A) != 0 && uiMode()) _uiInput.tryEmit(UiInput.Activate(false))
        for (b in 0..GamepadButton.HOME) setButton(b, false)
    }
}
