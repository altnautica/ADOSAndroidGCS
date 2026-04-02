package com.altnautica.gcs.data.mavlink

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ACK-based command queue with retry and timeout.
 * All outbound MAV_CMD_* commands can go through this queue
 * for reliable delivery with COMMAND_ACK verification.
 */
@Singleton
class CommandQueue @Inject constructor() {

    companion object {
        private const val TAG = "CommandQueue"
        private const val DEFAULT_TIMEOUT_MS = 3000L
        private const val DEFAULT_MAX_RETRIES = 3
    }

    private val pending = ConcurrentHashMap<Int, PendingCommand>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class PendingCommand(
        val commandId: Int,
        val deferred: CompletableDeferred<CommandResult>,
        val retryCount: Int = 0,
        val maxRetries: Int = DEFAULT_MAX_RETRIES,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val sendFn: suspend () -> Unit
    )

    data class CommandResult(
        val success: Boolean,
        val result: Int = 0,
        val message: String = ""
    ) {
        companion object {
            /** MAV_RESULT_ACCEPTED */
            const val RESULT_ACCEPTED = 0
            /** MAV_RESULT_TEMPORARILY_REJECTED */
            const val RESULT_TEMPORARILY_REJECTED = 1
            /** MAV_RESULT_DENIED */
            const val RESULT_DENIED = 2
            /** MAV_RESULT_UNSUPPORTED */
            const val RESULT_UNSUPPORTED = 3
            /** MAV_RESULT_FAILED */
            const val RESULT_FAILED = 4
            /** MAV_RESULT_IN_PROGRESS */
            const val RESULT_IN_PROGRESS = 5
        }
    }

    /**
     * Called by MavLinkParser when COMMAND_ACK is received.
     * Resolves the pending deferred for the matching command ID.
     */
    fun onCommandAck(commandId: Int, result: Int) {
        val cmd = pending.remove(commandId)
        if (cmd != null) {
            Log.d(TAG, "ACK received for command $commandId, result=$result")
            cmd.deferred.complete(
                CommandResult(
                    success = result == CommandResult.RESULT_ACCEPTED,
                    result = result,
                    message = resultToString(result)
                )
            )
        } else {
            Log.d(TAG, "ACK for unknown/already-resolved command $commandId")
        }
    }

    /**
     * Send a command and wait for ACK with retry on timeout.
     *
     * @param commandId The MAV_CMD enum value (used as key for ACK matching)
     * @param maxRetries Maximum retry attempts (default 3)
     * @param timeoutMs Per-attempt timeout in milliseconds (default 3000)
     * @param sendFn Suspend function that actually sends the MAVLink message
     * @return CommandResult indicating success/failure
     */
    suspend fun send(
        commandId: Int,
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        sendFn: suspend () -> Unit
    ): CommandResult {
        repeat(maxRetries) { attempt ->
            val deferred = CompletableDeferred<CommandResult>()
            pending[commandId] = PendingCommand(
                commandId = commandId,
                deferred = deferred,
                sendFn = sendFn
            )

            try {
                sendFn()
                Log.d(TAG, "Sent command $commandId (attempt ${attempt + 1}/$maxRetries)")
            } catch (e: Exception) {
                pending.remove(commandId)
                return CommandResult(false, message = "Send failed: ${e.message}")
            }

            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (result != null) return result
            pending.remove(commandId)
            Log.w(TAG, "Command $commandId timeout (attempt ${attempt + 1}/$maxRetries)")
        }
        return CommandResult(false, message = "Timeout after $maxRetries retries")
    }

    /**
     * Fire-and-forget: send without waiting for ACK.
     * Use for commands where ACK is unreliable (e.g., MAV_CMD_PREFLIGHT_STORAGE on ArduPilot).
     */
    suspend fun fireAndForget(sendFn: suspend () -> Unit) {
        try {
            sendFn()
        } catch (e: Exception) {
            Log.w(TAG, "Fire-and-forget send failed: ${e.message}")
        }
    }

    /** Returns true if there are pending commands waiting for ACK. */
    fun hasPending(): Boolean = pending.isNotEmpty()

    /** Number of currently pending commands. */
    fun pendingCount(): Int = pending.size

    fun shutdown() {
        scope.cancel()
        pending.values.forEach { it.deferred.cancel() }
        pending.clear()
    }

    private fun resultToString(result: Int): String = when (result) {
        CommandResult.RESULT_ACCEPTED -> "Accepted"
        CommandResult.RESULT_TEMPORARILY_REJECTED -> "Temporarily rejected"
        CommandResult.RESULT_DENIED -> "Denied"
        CommandResult.RESULT_UNSUPPORTED -> "Unsupported"
        CommandResult.RESULT_FAILED -> "Failed"
        CommandResult.RESULT_IN_PROGRESS -> "In progress"
        else -> "Unknown result ($result)"
    }
}
