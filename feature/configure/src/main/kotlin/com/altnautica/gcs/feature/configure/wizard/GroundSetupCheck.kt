package com.altnautica.gcs.feature.configure.wizard

import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether a fresh connection should open the setup wizard: the vehicle is
 * agent-connected, its flight controller has an identity, and it has never finished
 * the `ground` wizard. Returns that flight controller's uid, or null.
 */
@Singleton
class GroundSetupCheck @Inject constructor(private val backends: AgentSetupBackendSource) {
    suspend fun uidNeedingSetup(id: DeviceId): String? = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
        val backend = backends.current(id) ?: return@withTimeoutOrNull null
        val overview = (backend.overview() as? AgentResult.Ok)?.value ?: return@withTimeoutOrNull null
        overview.uid?.takeIf { overview.needsGroundSetup }
    }

    private companion object {
        const val CHECK_TIMEOUT_MS = 8_000L
    }
}
