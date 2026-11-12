package com.altnautica.gcs.feature.logs.dataflash

import java.io.File

/** Names a downloaded flight-controller log by its content: PX4 ULog or ArduPilot DataFlash. */
internal object LogFileType {

    /** ULog file magic: "ULog" then 0x01 0x12 0x35. */
    private val ULOG_MAGIC = byteArrayOf(0x55, 0x4C, 0x6F, 0x67, 0x01, 0x12, 0x35)

    fun isULog(file: File): Boolean {
        if (file.length() < ULOG_MAGIC.size) return false
        val head = ByteArray(ULOG_MAGIC.size)
        file.inputStream().use { input -> if (input.read(head) != head.size) return false }
        return head.contentEquals(ULOG_MAGIC)
    }

    /**
     * The downloaded [file] renamed to `.ulg` when it is a ULog, else unchanged (`.bin`).
     * A failed rename keeps the original name.
     */
    fun withSniffedExtension(file: File): File {
        if (!isULog(file)) return file
        val ulg = File(file.parentFile, file.nameWithoutExtension + ".ulg")
        ulg.delete()
        return if (file.renameTo(ulg)) ulg else file
    }
}
