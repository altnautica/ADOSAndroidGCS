package com.altnautica.gcs.feature.node.visor

import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.sensors.ClockStatus
import com.altnautica.gcs.core.agent.sensors.SensorsStatus
import com.altnautica.gcs.core.agent.sensors.StorageStatus
import com.altnautica.gcs.feature.node.common.PageProblem

/**
 * One overview block. [NotFitted] only when the agent says the service is
 * absent (`present: false`); a failed read keeps the last good value marked
 * [Ready.stale], or shows the failure when there was none.
 */
sealed interface Section<out T> {
    data object NotFitted : Section<Nothing>
    data class Ready<T>(val value: T, val stale: Boolean = false) : Section<T>
    data class Failed(val problem: PageProblem) : Section<Nothing>
}

data class ClockSourceUi(val id: String, val locked: Boolean, val sigmaNs: Long)

data class ClockUi(val locked: Boolean, val timebase: String?, val sigmaNs: Long, val sources: List<ClockSourceUi>)

enum class StorageState { Ok, NearFull, Full, Unknown }

data class StorageUi(
    val state: StorageState,
    val root: String?,
    val usedBytes: Long?,
    val budgetBytes: Long?,
    val freeBytes: Long?,
    val sessions: Int?,
    val uploaded: Int?,
    val writeMbps: Double?,
)

data class LinkUi(val connected: Boolean, val label: String?)

data class SensorRateUi(val id: String, val role: String?, val rateHz: Double?, val expectedHz: Double?, val slow: Boolean)

data class GnssBriefUi(val fix: String?, val sats: Int?, val rtk: String?)

data class SensorsUi(
    val rigId: String?,
    val calibVersion: String?,
    val hub: LinkUi?,
    val fc: LinkUi?,
    val sensors: List<SensorRateUi>,
    val gnss: GnssBriefUi?,
) {
    val slowCount: Int get() = sensors.count { it.slow }
}

/** The Visor rows on the overview tab. */
data class VisorOverview(
    val clock: Section<ClockUi>,
    val storage: Section<StorageUi>,
    val sensors: Section<SensorsUi>,
)

object VisorOverviewMapper {

    fun clock(c: ClockStatus) = ClockUi(c.locked, c.timebase, c.sigmaNs, c.sources.map { ClockSourceUi(it.id, it.locked, it.sigmaNs) })

    fun storage(s: StorageStatus) = StorageUi(
        state = when (s.capture?.state) {
            "ok" -> StorageState.Ok
            "near_full" -> StorageState.NearFull
            "full" -> StorageState.Full
            else -> StorageState.Unknown
        },
        root = s.captureRoot,
        usedBytes = s.capture?.usedBytes,
        budgetBytes = s.capture?.budgetBytes,
        freeBytes = s.volumes.firstOrNull { it.isCapture }?.freeBytes,
        sessions = s.capture?.sessions,
        uploaded = s.capture?.uploaded,
        writeMbps = s.writeProbe?.mbps,
    )

    fun sensors(s: SensorsStatus) = SensorsUi(
        rigId = s.rigId,
        calibVersion = s.calibVersion,
        hub = s.hub?.let { LinkUi(it.connected, it.fw) },
        fc = s.fc?.let { LinkUi(it.connected, it.autopilot) },
        sensors = s.sensors.map { r ->
            val expected = r.expectedHz
            val rate = r.rateHz
            // Below 90 % of the expected rate, or silent while a rate is expected.
            val slow = expected != null && expected > 0 && (rate == null || rate < expected * SLOW_FRACTION)
            SensorRateUi(r.id, r.role, rate, expected, slow)
        },
        gnss = s.gnss?.let { GnssBriefUi(it.fix, it.sats, it.rtk) },
    )

    /** The next value of a block from one read; see [Section] for the rules. */
    fun <D, T> next(prev: Section<T>?, result: AgentResult<D>, present: (D) -> Boolean, map: (D) -> T): Section<T> = when (result) {
        is AgentResult.Ok -> if (present(result.value)) Section.Ready(map(result.value)) else Section.NotFitted
        is AgentResult.Err -> if (prev is Section.Ready) prev.copy(stale = true) else Section.Failed(PageProblem.from(result.error))
    }

    private const val SLOW_FRACTION = 0.9
}
