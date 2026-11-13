package com.altnautica.gcs.core.video

import android.content.Context
import android.content.pm.ApplicationInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Video switches a debuggable build reads from system properties (`adb shell setprop`), so a
 * bench can flip them without a rebuild; a flip takes effect on the next app start. A release
 * build never reads them. Each property is read once per process; the first read starts a
 * `getprop` process: call it off the main thread.
 */
object DebugProperties {
    /** "1" forces byte-buffer decoding, "0" forces texture decoding. */
    const val BYTE_BUFFER = "debug.ados.video.bytebuffer"

    /** "1" shows the per-hop frame rates over the main video. */
    const val OVERLAY = "debug.ados.video.overlay"

    /** How long a `getprop` process may run before it is abandoned. */
    internal const val GETPROP_TIMEOUT_MS = 2_000L

    private val cache = PropertyCache(::getprop)

    fun read(context: Context, name: String): String? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return cache.get(name)
    }

    /** One `getprop` run; null when the process fails or does not finish in time. Its output is one line. */
    private fun getprop(name: String): String? {
        val process = runCatching { ProcessBuilder("getprop", name).redirectErrorStream(true).start() }.getOrNull() ?: return null
        return try {
            if (!process.waitFor(GETPROP_TIMEOUT_MS, TimeUnit.MILLISECONDS) || process.exitValue() != 0) return null
            process.inputStream.bufferedReader().use { it.readText().trim() }
        } catch (_: Exception) {
            null
        } finally {
            process.destroy()
        }
    }
}

/** Property values read once per name through [read]; an unset or unreadable property reads null. */
internal class PropertyCache(private val read: (String) -> String?) {
    private val values = ConcurrentHashMap<String, String>()

    fun get(name: String): String? = values.computeIfAbsent(name) { read(it).orEmpty() }.ifEmpty { null }
}
