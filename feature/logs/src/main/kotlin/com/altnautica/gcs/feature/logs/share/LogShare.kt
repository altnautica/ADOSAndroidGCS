package com.altnautica.gcs.feature.logs.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.altnautica.gcs.feature.logs.R
import java.io.File

/** Distinct provider class so it merges beside any other FileProvider in the app. */
class LogFileProvider : FileProvider()

/** Authority of [LogFileProvider], declared in this module's manifest. */
fun logFileAuthority(context: Context): String = "${context.packageName}.logs.files"

/** Opens the system share sheet for a log file in app-specific storage. */
fun shareLogFile(context: Context, file: File) {
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, logFileAuthority(context), file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.logs_share_chooser))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
