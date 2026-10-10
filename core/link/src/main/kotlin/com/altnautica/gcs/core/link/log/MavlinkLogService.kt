package com.altnautica.gcs.core.link.log

import com.altnautica.gcs.core.link.mavlink.MavlinkLink
import com.altnautica.gcs.core.link.mavlink.withInbox
import com.altnautica.gcs.core.mavlink.messages.LogData
import com.altnautica.gcs.core.mavlink.messages.LogEntry as LogEntryMsg
import com.altnautica.gcs.core.mavlink.messages.LogErase
import com.altnautica.gcs.core.mavlink.messages.LogRequestData
import com.altnautica.gcs.core.mavlink.messages.LogRequestEnd
import com.altnautica.gcs.core.mavlink.messages.LogRequestList
import com.altnautica.gcs.core.model.WriteResult
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Dataflash download over `LOG_REQUEST_DATA` / `LOG_DATA` (90-byte chunks).
 * Data is requested in windows from the first missing byte; a window that
 * stalls for 1 s is re-requested from where it stopped, five times at most.
 */
class MavlinkLogService(private val link: MavlinkLink) : LogService {

    override suspend fun list(): LogListResult {
        val target = link.target.value ?: return LogListResult.Failed(REASON_NO_LINK)
        return link.withInbox({ it is LogEntryMsg }) { inbox ->
            val entries = sortedMapOf<Int, LogEntry>()
            var expected = -1
            repeat(LIST_RETRIES) {
                if (link.send(LogRequestList(target.sysId, target.compId, 0, 0xFFFF)) != WriteResult.Sent) {
                    return@withInbox LogListResult.Failed(REASON_WRITE)
                }
                while (true) {
                    val e = inbox.receive(LIST_IDLE_MS) as? LogEntryMsg ?: break
                    expected = e.numLogs
                    if (e.numLogs == 0) return@withInbox LogListResult.Success(emptyList())
                    entries[e.id] = LogEntry(e.id, e.size, e.timeUtc)
                    if (entries.size >= expected) return@withInbox LogListResult.Success(entries.values.toList())
                }
                if (expected >= 0) return@withInbox LogListResult.Success(entries.values.toList())
            }
            LogListResult.Failed(REASON_TIMEOUT)
        }
    }

    override fun download(entry: LogEntry, destination: File): Flow<LogDownloadProgress> = channelFlow {
        val target = link.target.value
        if (target == null) {
            send(LogDownloadProgress.Failed(REASON_NO_LINK, 0))
            return@channelFlow
        }
        destination.parentFile?.mkdirs()
        RandomAccessFile(destination, "rw").use { file ->
            var offset = file.length().coerceAtMost(entry.sizeBytes)
            file.setLength(offset)
            send(LogDownloadProgress.Running(offset, entry.sizeBytes))
            try {
                link.withInbox({ it is LogData && it.id == entry.id }) { inbox ->
                    var stalls = 0
                    var lastReported = offset
                    while (offset < entry.sizeBytes) {
                        val window = minOf(WINDOW_BYTES, entry.sizeBytes - offset)
                        inbox.drain()
                        if (link.send(LogRequestData(target.sysId, target.compId, entry.id, offset, window)) != WriteResult.Sent) {
                            send(LogDownloadProgress.Failed(REASON_WRITE, offset))
                            return@withInbox
                        }
                        val windowEnd = offset + window
                        while (offset < windowEnd) {
                            val d = inbox.receive(DATA_TIMEOUT_MS) as? LogData ?: break
                            if (d.ofs != offset) continue
                            if (d.count == 0) {
                                offset = entry.sizeBytes
                                break
                            }
                            file.seek(offset)
                            file.write(d.data, 0, d.count)
                            offset += d.count
                            stalls = 0
                            if (offset - lastReported >= PROGRESS_STEP_BYTES) {
                                lastReported = offset
                                send(LogDownloadProgress.Running(offset, entry.sizeBytes))
                            }
                        }
                        if (offset < windowEnd && offset < entry.sizeBytes && ++stalls > STALL_RETRIES) {
                            send(LogDownloadProgress.Failed(REASON_TIMEOUT, offset))
                            return@withInbox
                        }
                    }
                    send(LogDownloadProgress.Running(offset, entry.sizeBytes))
                    send(LogDownloadProgress.Done(destination))
                }
            } finally {
                withContext(NonCancellable) { link.send(LogRequestEnd(target.sysId, target.compId)) }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun eraseAll(): Boolean {
        val target = link.target.value ?: return false
        return link.send(LogErase(target.sysId, target.compId)) == WriteResult.Sent
    }

    companion object {
        const val WINDOW_BYTES = 90L * 512
        const val DATA_TIMEOUT_MS = 1_000L
        const val STALL_RETRIES = 5
        const val LIST_IDLE_MS = 2_000L
        const val LIST_RETRIES = 3
        const val PROGRESS_STEP_BYTES = 64L * 1024
        const val REASON_NO_LINK = "noFcLink"
        const val REASON_WRITE = "writeFailed"
        const val REASON_TIMEOUT = "timeout"
    }
}
