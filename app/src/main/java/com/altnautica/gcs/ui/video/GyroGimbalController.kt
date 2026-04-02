package com.altnautica.gcs.ui.video

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.altnautica.gcs.data.mavlink.MavLinkCommandSender
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gyro-based gimbal controller using TYPE_GAME_ROTATION_VECTOR sensor.
 * Captures initial device orientation on start() and sends pitch/yaw deltas
 * to the gimbal at 10Hz. Relative mode only: moving the device from its
 * baseline orientation moves the gimbal proportionally.
 */
@Singleton
class GyroGimbalController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val commandSender: MavLinkCommandSender,
) : SensorEventListener {

    companion object {
        private const val TAG = "GyroGimbalController"
        private const val SEND_INTERVAL_MS = 100L // 10Hz
        private const val RAD_TO_DEG = 57.2957795f
        private const val PITCH_SCALE = 45f // Max degrees of gimbal pitch per full device tilt
        private const val YAW_SCALE = 45f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sendJob: Job? = null

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    // Baseline orientation captured on start()
    private var baselinePitch = 0f
    private var baselineYaw = 0f
    private var baselineCaptured = false

    // Current orientation from sensor
    private var currentPitch = 0f
    private var currentYaw = 0f

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    fun start() {
        if (rotationSensor == null) {
            Log.w(TAG, "TYPE_GAME_ROTATION_VECTOR not available on this device")
            return
        }
        baselineCaptured = false
        _active.value = true

        sensorManager.registerListener(
            this,
            rotationSensor,
            SensorManager.SENSOR_DELAY_GAME,
        )

        sendJob = scope.launch {
            while (isActive && _active.value) {
                if (baselineCaptured) {
                    val deltaPitch = (currentPitch - baselinePitch) * RAD_TO_DEG
                    val deltaYaw = (currentYaw - baselineYaw) * RAD_TO_DEG
                    val gimbalPitch = (deltaPitch / 90f * PITCH_SCALE).coerceIn(-90f, 30f)
                    val gimbalYaw = (deltaYaw / 90f * YAW_SCALE).coerceIn(-180f, 180f)
                    commandSender.sendGimbalPitchYaw(gimbalPitch, gimbalYaw)
                }
                delay(SEND_INTERVAL_MS)
            }
        }

        Log.i(TAG, "Gyro gimbal started")
    }

    fun stop() {
        _active.value = false
        sendJob?.cancel()
        sendJob = null
        sensorManager.unregisterListener(this)
        baselineCaptured = false
        Log.i(TAG, "Gyro gimbal stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        // orientationAngles: [0]=azimuth (yaw), [1]=pitch, [2]=roll, all in radians
        currentPitch = orientationAngles[1]
        currentYaw = orientationAngles[0]

        if (!baselineCaptured) {
            baselinePitch = currentPitch
            baselineYaw = currentYaw
            baselineCaptured = true
            Log.d(TAG, "Baseline captured: pitch=$baselinePitch yaw=$baselineYaw")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }
}
