package com.altnautica.gcs.data.mavlink

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        return sendInternal(commandId, 0, maxRetries, timeoutMs, sendFn)
    }

    private suspend fun sendInternal(
        commandId: Int,
        retryCount: Int,
        maxRetries: Int,
        timeoutMs: Long,
        sendFn: suspend () -> Unit
    ): CommandResult {
        val deferred = CompletableDeferred<CommandResult>()
        val cmd = PendingCommand(
            commandId = commandId,
            deferred = deferred,
            retryCount = retryCount,
            maxRetries = maxRetries,
            timeoutMs = timeoutMs,
            sendFn = sendFn
        )
        pending[commandId] = cmd

        try {
            sendFn()
            Log.d(TAG, "Sent command $commandId (attempt ${retryCount + 1}/${maxRetries + 1})")
        } catch (e: Exception) {
            pending.remove(commandId)
            return CommandResult(false, message = "Send failed: ${e.message}")
        }

        // Start timeout watcher
        val timeoutJob = scope.launch {
            delay(timeoutMs)
            if (pending.remove(commandId) != null) {
                if (retryCount < maxRetries) {
                    Log.d(TAG, "Timeout for command $commandId, retrying (${retryCount + 1}/$maxRetries)")
                    val retryResult = sendInternal(commandId, retryCount + 1, maxRetries, timeoutMs, sendFn)
                    deferred.complete(retryResult)
                } else {
                    Log.w(TAG, "Command $commandId timed out after ${maxRetries + 1} attempts")
                    deferred.complete(
                        CommandResult(false, message = "Timeout after ${maxRetries + 1} attempts")
                    )
                }
            }
        }

        val result = deferred.await()
        timeoutJob.cancel()
        return result
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
