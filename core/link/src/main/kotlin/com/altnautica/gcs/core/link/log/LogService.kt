package com.altnautica.gcs.core.link.log

import java.io.File
import kotlinx.coroutines.flow.Flow

/** One onboard (dataflash) log as listed by `LOG_ENTRY`. */
data class LogEntry(
    val id: Int,
    val sizeBytes: Long,
    /** UTC seconds since the epoch; 0 when the autopilot had no clock. */
    val timeUtcS: Long,
)

sealed interface LogListResult {
    data class Success(val entries: List<LogEntry>) : LogListResult
    data class Failed(val reason: String) : LogListResult
}

sealed interface LogDownloadProgress {
    data class Running(val receivedBytes: Long, val totalBytes: Long) : LogDownloadProgress
    data class Done(val file: File) : LogDownloadProgress
    data class Failed(val reason: String, val receivedBytes: Long) : LogDownloadProgress
}

/** Onboard log list and download (`LOG_REQUEST_LIST` / `LOG_REQUEST_DATA`). */
interface LogService {
    suspend fun list(): LogListResult

    /**
     * Downloads [entry] into [destination]. An existing partial file resumes
     * from its length. Cancelling the collector stops the transfer and sends
     * `LOG_REQUEST_END`; the partial file stays for a later resume.
     */
    fun download(entry: LogEntry, destination: File): Flow<LogDownloadProgress>

    /** `LOG_ERASE`: deletes every onboard log. */
    suspend fun eraseAll(): Boolean
}
