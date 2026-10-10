package com.altnautica.gcs.feature.configure.params

import android.content.Context
import com.altnautica.gcs.core.model.FcVariant
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Which bundled documentation file describes a flight controller. */
object ParamMetadataKey {
    /** MAVLink `MAV_TYPE` values grouped by the ArduPilot firmware that flies them. */
    private val PLANE_TYPES = setOf(1, 16, 19, 20, 21, 22, 23, 24, 25)
    private val ROVER_TYPES = setOf(10, 11)
    private const val SUBMARINE = 12

    fun forVehicle(variant: FcVariant, mavType: Int?): String? = when (variant) {
        FcVariant.MavlinkPx4 -> "px4"
        FcVariant.MavlinkArdupilot -> when (mavType) {
            in PLANE_TYPES -> "ardupilot-plane"
            in ROVER_TYPES -> "ardupilot-rover"
            SUBMARINE -> "ardupilot-sub"
            else -> "ardupilot-copter"
        }
        FcVariant.Msp, FcVariant.Unknown -> null
    }
}

/** Loads documentation lazily per firmware and keeps the parsed set for the process lifetime. */
@Singleton
class ParamMetadataRepository internal constructor(
    private val open: (String) -> InputStream,
) {
    @Inject constructor(@ApplicationContext context: Context) :
        this({ key: String -> context.assets.open("param-metadata/$key.json.gz") })

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, ParamMetadataSet>()

    /** Returns null when no documentation file exists for [key]. */
    suspend fun load(key: String): ParamMetadataSet? = mutex.withLock {
        cache[key] ?: withContext(Dispatchers.IO) {
            runCatching {
                GZIPInputStream(open(key)).bufferedReader().use { ParamMetadataParser.parse(it.readText()) }
            }.getOrNull()
        }?.also { cache[key] = it }
    }
}
