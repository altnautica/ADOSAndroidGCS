package com.altnautica.gcs.data.voice

import com.altnautica.gcs.data.mavlink.MavLinkCommandSender
import com.altnautica.gcs.data.telemetry.FlightMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class VoiceCommand(val label: String) {
    data object Arm : VoiceCommand("Arm motors")
    data object Disarm : VoiceCommand("Disarm motors")
    data class Takeoff(val altitude: Float = 10f) : VoiceCommand("Takeoff to ${altitude.toInt()}m")
    data object Land : VoiceCommand("Land")
    data object Rtl : VoiceCommand("Return to launch")
    data class SetMode(val mode: FlightMode) : VoiceCommand("Set mode: ${mode.label}")
}

/**
 * Manages voice command lifecycle: propose -> confirm/cancel.
 *
 * Commands are never executed immediately. They are held in a pending
 * state until the user explicitly confirms via the confirmation dialog.
 * This prevents accidental arm/disarm/takeoff from misrecognized speech.
 */
@Singleton
class VoiceCommandExecutor @Inject constructor(
    private val commandSender: MavLinkCommandSender,
) {

    private val _pendingCommand = MutableStateFlow<VoiceCommand?>(null)
    val pendingCommand: StateFlow<VoiceCommand?> = _pendingCommand.asStateFlow()

    fun propose(command: VoiceCommand) {
        _pendingCommand.value = command
    }

    suspend fun confirm() {
        _pendingCommand.value?.let { cmd ->
            when (cmd) {
                is VoiceCommand.Arm -> commandSender.sendArm()
                is VoiceCommand.Disarm -> commandSender.sendDisarm()
                is VoiceCommand.Takeoff -> commandSender.sendTakeoff(cmd.altitude)
                is VoiceCommand.Land -> commandSender.sendLand()
                is VoiceCommand.Rtl -> commandSender.sendRtl()
                is VoiceCommand.SetMode -> commandSender.sendSetMode(cmd.mode)
            }
        }
        _pendingCommand.value = null
    }

    fun cancel() {
        _pendingCommand.value = null
    }
}
