package com.altnautica.gcs.data.mavlink

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires MavLinkParser callbacks to CommandQueue and ParameterManager.
 * Inject this singleton and call [initialize] once at app startup
 * (e.g., in Application.onCreate or the main ViewModel).
 */
@Singleton
class MavLinkWiring @Inject constructor(
    private val parser: MavLinkParser,
    private val commandQueue: CommandQueue,
    private val parameterManager: ParameterManager
) {

    companion object {
        private const val TAG = "MavLinkWiring"
    }

    @Volatile
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true

        parser.onCommandAck = { commandId, result ->
            commandQueue.onCommandAck(commandId, result)
        }

        parser.onParamValue = { name, value, type, count, index ->
            parameterManager.onParamValue(name, value, type, count, index)
        }

        Log.i(TAG, "MAVLink wiring initialized: parser -> commandQueue + parameterManager")
    }

    fun shutdown() {
        commandQueue.shutdown()
        parameterManager.shutdown()
        parser.onCommandAck = null
        parser.onParamValue = null
        initialized = false
    }
}
