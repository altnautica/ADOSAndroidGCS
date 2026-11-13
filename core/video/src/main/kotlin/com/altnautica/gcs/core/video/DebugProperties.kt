package com.altnautica.gcs.core.video

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Video switches a debuggable build reads from system properties (`adb shell setprop`), so a
 * bench can flip them without a rebuild. A release build never reads them. Each read starts a
 * `getprop` process: call it off the main thread.
 */
object DebugProperties {
    /** "1" forces byte-buffer decoding, "0" forces texture decoding; read once per process. */
    const val BYTE_BUFFER = "debug.ados.video.bytebuffer"

    /** "1" shows the per-hop frame rates over the main video. */
    const val OVERLAY = "debug.ados.video.overlay"

    fun read(context: Context, name: String): String? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", name)).inputStream.bufferedReader().use { it.readText().trim() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
