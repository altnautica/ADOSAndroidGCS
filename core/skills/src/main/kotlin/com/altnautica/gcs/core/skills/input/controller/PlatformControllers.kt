package com.altnautica.gcs.core.skills.input.controller

import android.content.Context
import android.hardware.BatteryState
import android.hardware.input.InputManager
import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.view.InputDevice
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

/**
 * A controller rumble: [pulsesMs] on-times separated by [gapMs] of rest, at [amplitude]
 * (0..1). The presets are the cockpit's controller patterns.
 */
data class Rumble(val pulsesMs: List<Int>, val gapMs: Int, val amplitude: Float) {
    companion object {
        /** One short pulse: a mode or skill change. */
        val ModeChange = Rumble(listOf(80), 0, 0.5f)

        /** Two short pulses: a warning. */
        val Warning = Rumble(listOf(80, 80), 120, 0.6f)

        /** Three long pulses: a critical event. */
        val Critical = Rumble(listOf(300, 300, 300), 200, 1f)
    }
}

/**
 * Platform access to attached controllers: enumeration, battery, rumble and the light
 * bar. Battery, rumble and lights need API 31; below it they report nothing and do
 * nothing. Every hardware call is best effort.
 */
internal class PlatformControllers(context: Context) {
    private val inputManager = context.getSystemService(InputManager::class.java)
    private val lights: LightSessions? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LightSessions() else null

    /** Calls [onChange] on the main thread whenever a device is added, removed or changed. */
    fun listen(onChange: () -> Unit) {
        inputManager?.registerInputDeviceListener(
            object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) = onChange()
                override fun onInputDeviceRemoved(deviceId: Int) = onChange()
                override fun onInputDeviceChanged(deviceId: Int) = onChange()
            },
            Handler(Looper.getMainLooper()),
        )
    }

    fun read(): List<ControllerInfo> = InputDevice.getDeviceIds().asList().mapNotNull { id ->
        val device = InputDevice.getDevice(id) ?: return@mapNotNull null
        val controller = !device.isVirtual &&
            (device.supportsSource(InputDevice.SOURCE_GAMEPAD) || device.supportsSource(InputDevice.SOURCE_JOYSTICK))
        if (!controller) return@mapNotNull null
        val battery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Api31.battery(device) else null
        ControllerInfo(
            deviceId = id,
            name = device.name.orEmpty(),
            vendorId = device.vendorId,
            productId = device.productId,
            family = controllerFamily(device.vendorId),
            batteryPct = battery?.first,
            charging = battery?.second == true,
        )
    }

    fun rumble(deviceId: Int, pattern: Rumble) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val device = InputDevice.getDevice(deviceId) ?: return
        runCatching { Api31.rumble(device, pattern) }
    }

    /** Sets the light bar of [deviceId]; null restores its default colour. */
    fun setLightbar(deviceId: Int, argb: Int?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) lights?.set(deviceId, argb)
    }

    /** Restores every light bar this app coloured. */
    fun clearLights() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) lights?.retain(emptySet())
    }

    /** Closes light sessions of devices that are gone. */
    fun retain(deviceIds: Set<Int>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) lights?.retain(deviceIds)
    }

    fun capabilities(deviceId: Int): ControllerCapabilities {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return ControllerCapabilities.None
        val device = InputDevice.getDevice(deviceId) ?: return ControllerCapabilities.None
        return runCatching { Api31.capabilities(device) }.getOrDefault(ControllerCapabilities.None)
    }

    fun motionRanges(deviceId: Int): List<AxisRange> = InputDevice.getDevice(deviceId)?.motionRanges.orEmpty()
        .filter { it.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK) }
        .map { AxisRange(MotionEvent.axisToString(it.axis), it.min, it.max, it.flat) }
        .distinctBy { it.name }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31 {
        /** Battery percent (null when unknown) and charging, or null when no battery is present. */
        fun battery(device: InputDevice): Pair<Int?, Boolean>? {
            val state = device.batteryState
            if (!state.isPresent) return null
            val capacity = state.capacity
            val pct = if (capacity.isNaN()) null else (capacity * 100f).roundToInt().coerceIn(0, 100)
            return pct to (state.status == BatteryState.STATUS_CHARGING)
        }

        fun rumble(device: InputDevice, pattern: Rumble) {
            val vibrator = device.vibratorManager.defaultVibrator
            if (!vibrator.hasVibrator()) return
            if (pattern.pulsesMs.isEmpty()) return
            val level = (pattern.amplitude * 255f).roundToInt().coerceIn(1, 255)
            val timings = ArrayList<Long>(pattern.pulsesMs.size * 2)
            val amplitudes = ArrayList<Int>(pattern.pulsesMs.size * 2)
            pattern.pulsesMs.forEachIndexed { i, on ->
                timings += if (i == 0) 0L else pattern.gapMs.toLong()
                amplitudes += 0
                timings += on.toLong()
                amplitudes += level
            }
            val effect = VibrationEffect.createWaveform(timings.toLongArray(), amplitudes.toIntArray(), -1)
            vibrator.vibrate(effect)
        }

        fun capabilities(device: InputDevice) = ControllerCapabilities(
            rumble = device.vibratorManager.vibratorIds.isNotEmpty(),
            lightbar = device.lightsManager.lights.any { it.type == Light.LIGHT_TYPE_INPUT && it.hasRgbControl() },
        )
    }

    /** One open lights session per device; closing it restores the controller's default colour. */
    @RequiresApi(Build.VERSION_CODES.S)
    private class LightSessions {
        private val sessions = mutableMapOf<Int, LightsManager.LightsSession>()

        fun set(deviceId: Int, argb: Int?) {
            if (argb == null) {
                close(deviceId)
                return
            }
            runCatching {
                val device = InputDevice.getDevice(deviceId) ?: return
                val manager = device.lightsManager
                val light = manager.lights.firstOrNull { it.type == Light.LIGHT_TYPE_INPUT && it.hasRgbControl() } ?: return
                val session = sessions.getOrPut(deviceId) { manager.openSession() }
                session.requestLights(LightsRequest.Builder().addLight(light, LightState.Builder().setColor(argb).build()).build())
            }
        }

        fun retain(deviceIds: Set<Int>) {
            (sessions.keys - deviceIds).forEach(::close)
        }

        private fun close(deviceId: Int) {
            sessions.remove(deviceId)?.let { runCatching { it.close() } }
        }
    }
}
