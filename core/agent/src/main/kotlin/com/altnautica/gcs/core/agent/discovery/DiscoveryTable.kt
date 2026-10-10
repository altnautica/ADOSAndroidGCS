package com.altnautica.gcs.core.agent.discovery

/** One agent seen on `_ados._tcp`, merged from its SRV/A and TXT records. */
data class DiscoveredAgent(
    val serviceName: String,
    val deviceId: String?,
    val name: String?,
    val version: String?,
    val board: String?,
    val paired: Boolean?,
    val profile: String?,
    val role: String?,
    /** Pairing code, advertised only while the node is unpaired. */
    val code: String?,
    val host: String,
    val port: Int,
    val lastSeenMs: Long,
) {
    val baseUrl: String
        get() = "http://${if (host.contains(':')) "[$host]" else host}:$port/"
}

/** Parses the agent's TXT record (`device_id, version, board, name, paired, profile, role?, code?`). */
object AgentTxt {

    fun parse(attributes: Map<String, ByteArray?>): Map<String, String> =
        attributes.mapNotNull { (k, v) -> v?.let { k.lowercase() to it.decodeToString().trim() } }
            .filter { it.second.isNotEmpty() }
            .toMap()

    fun toAgent(serviceName: String, host: String, port: Int, txt: Map<String, String>, nowMs: Long) = DiscoveredAgent(
        serviceName = serviceName,
        deviceId = txt["device_id"],
        name = txt["name"],
        version = txt["version"],
        board = txt["board"],
        paired = txt["paired"]?.lowercase()?.let { it == "true" || it == "1" },
        profile = txt["profile"],
        role = txt["role"],
        code = txt["code"]?.uppercase()?.takeIf(PairingCode::isValid),
        host = host,
        port = port,
        lastSeenMs = nowMs,
    )
}

/** Pairing code rules: the agent's alphabet excludes I, O, 0 and 1. */
object PairingCode {
    private val VALID = Regex("^[A-HJ-NP-Z2-9]{4,12}$")

    fun normalize(input: String): String = input.trim().uppercase().replace("-", "").replace(" ", "")

    fun isValid(code: String): Boolean = VALID.matches(code)
}

/**
 * The live set of discovered agents, one row per `device_id` (a node seen on
 * two interfaces is one row carrying its most recent address). Rows without a
 * device id are keyed by service name.
 */
class DiscoveryTable {
    private val rows = LinkedHashMap<String, DiscoveredAgent>()

    @Synchronized
    fun upsert(agent: DiscoveredAgent): List<DiscoveredAgent> {
        val key = agent.deviceId ?: "svc:${agent.serviceName}"
        // A node that re-announced under a new service name replaces its old row.
        rows.entries.removeAll { (k, v) -> k != key && agent.deviceId != null && v.serviceName == agent.serviceName && v.deviceId == null }
        rows[key] = agent
        return snapshot()
    }

    @Synchronized
    fun remove(serviceName: String): List<DiscoveredAgent> {
        rows.entries.removeAll { it.value.serviceName == serviceName }
        return snapshot()
    }

    @Synchronized
    fun snapshot(): List<DiscoveredAgent> = rows.values.sortedWith(compareBy({ it.name ?: "" }, { it.deviceId ?: it.serviceName }))

    @Synchronized
    fun clear() = rows.clear()
}
