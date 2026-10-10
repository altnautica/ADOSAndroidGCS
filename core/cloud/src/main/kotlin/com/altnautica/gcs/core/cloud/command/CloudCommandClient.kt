package com.altnautica.gcs.core.cloud.command

import com.altnautica.gcs.core.cloud.auth.CloudAuthState
import com.altnautica.gcs.core.cloud.auth.CloudSessionState
import com.altnautica.gcs.core.cloud.convex.CloudJson
import com.altnautica.gcs.core.cloud.convex.ConvexGateway
import com.altnautica.gcs.core.link.queue.QueuedCommandSink
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.VehicleCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/** A queued command's delivery state, as the cloud reports it. */
sealed interface QueuedCommandStatus {
    data object Pending : QueuedCommandStatus
    data object Delivering : QueuedCommandStatus
    data class Completed(val message: String?) : QueuedCommandStatus
    data class Failed(val message: String?) : QueuedCommandStatus
    data object Unknown : QueuedCommandStatus
}

/**
 * Flight commands through the cloud command queue (store and forward). A
 * successful enqueue is [CommandResult.Queued]: the vehicle has not seen it
 * yet, and the row is dropped if the node does not take it within [TTL_MS].
 */
@Singleton
class CloudCommandClient @Inject constructor(
    private val gateway: ConvexGateway,
    private val auth: CloudAuthState,
) {
    suspend fun run(node: DeviceId, command: VehicleCommand): CommandResult {
        if (!auth.active.value || auth.state.value != CloudSessionState.SignedIn) {
            return CommandResult.Denied("Not signed in to the cloud")
        }
        val (cmd, args) = verbOf(command) ?: return CommandResult.Unsupported
        return try {
            val json = gateway.mutation("cmdDroneCommands:enqueueCommand", enqueueArgs(node, cmd, args))
            val id = CloudJson.decodeFromJsonElement(EnqueueResult.serializer(), json).commandId
            CommandResult.Queued(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CommandResult.Denied(e.message ?: "Cloud queue refused the command")
        }
    }

    fun status(commandId: String): Flow<QueuedCommandStatus> =
        gateway.subscribe("cmdDroneCommands:getCommandStatus", mapOf("commandId" to commandId)).map { r ->
            r.map(::parseStatus).getOrDefault(QueuedCommandStatus.Unknown)
        }

    @Serializable
    private data class EnqueueResult(val commandId: String)

    @Serializable
    private data class CommandRow(val status: String? = null, val result: Outcome? = null)

    @Serializable
    private data class Outcome(val success: Boolean = false, val message: String? = null)

    companion object {
        const val TTL_MS = 10_000L

        /** The agent command verb and positional arguments; null when the queue cannot carry it. */
        fun verbOf(command: VehicleCommand): Pair<String, List<Any?>>? = when (command) {
            is VehicleCommand.Arm -> "arm" to emptyList()
            is VehicleCommand.Disarm -> "disarm" to emptyList()
            is VehicleCommand.Takeoff -> "takeoff" to listOf(command.altM.toDouble())
            VehicleCommand.Land -> "land" to emptyList()
            VehicleCommand.ReturnToLaunch -> "rtl" to emptyList()
            is VehicleCommand.SetMode -> "mode" to listOf(command.mode.name)
            VehicleCommand.PauseMission -> "pausemission" to emptyList()
            VehicleCommand.ResumeMission -> "resumemission" to emptyList()
            VehicleCommand.Kill -> "killswitch" to emptyList()
            is VehicleCommand.FlyTo, is VehicleCommand.MotorTest -> null
        }

        fun enqueueArgs(node: DeviceId, cmd: String, args: List<Any?>): Map<String, Any?> = mapOf(
            "deviceId" to node.value,
            "command" to "send_command",
            "args" to mapOf("cmd" to cmd, "args" to args),
            "ttlMs" to TTL_MS.toDouble(),
        )

        internal fun parseStatus(json: JsonElement): QueuedCommandStatus {
            val row = runCatching { CloudJson.decodeFromJsonElement(CommandRow.serializer(), json) }.getOrNull()
                ?: return QueuedCommandStatus.Unknown
            return when (row.status) {
                "pending" -> QueuedCommandStatus.Pending
                "delivering" -> QueuedCommandStatus.Delivering
                "completed" -> if (row.result?.success == false) {
                    QueuedCommandStatus.Failed(row.result.message)
                } else {
                    QueuedCommandStatus.Completed(row.result?.message)
                }
                "failed" -> QueuedCommandStatus.Failed(row.result?.message)
                else -> QueuedCommandStatus.Unknown
            }
        }
    }
}

/** Routes vehicles reachable only through the cloud queue to [CloudCommandClient]. */
@Singleton
class CloudQueuedCommandSink @Inject constructor(
    private val client: CloudCommandClient,
) : QueuedCommandSink {
    override fun supports(reach: Reach): Boolean = reach == Reach.CloudQueue

    override suspend fun run(node: DeviceId, command: VehicleCommand): CommandResult = client.run(node, command)
}
