package com.altnautica.gcs.core.extensions.manifest

import kotlinx.serialization.json.JsonElement

/** Lifecycle status the agent reports for an install. */
enum class InstallStatus {
    Installed, Enabled, Running, Disabled, Crashed, Removed, Unknown;

    val isEnabled: Boolean get() = this == Enabled || this == Running

    companion object {
        fun fromWire(v: String?): InstallStatus = when (v) {
            "installed" -> Installed
            "enabled" -> Enabled
            "running" -> Running
            "disabled" -> Disabled
            "crashed" -> Crashed
            "removed" -> Removed
            else -> Unknown
        }
    }
}

data class PermissionGrant(val id: String, val granted: Boolean)

data class DeclaredPermission(val id: String, val required: Boolean, val label: String?, val description: String?, val risk: String?)

/** How the extension's GCS half runs. Inline modules cannot run on this app. */
enum class GcsIsolation { Iframe, Inline }

/** One installed extension on a node, from `GET /api/plugins/{id}`. */
data class ExtensionDetail(
    val pluginId: String,
    val name: String,
    val version: String,
    val risk: String?,
    val status: InstallStatus,
    val permissions: List<PermissionGrant>,
    val declaredPermissions: List<DeclaredPermission>,
    val grantedCapabilities: Set<String>,
    val entrypoint: String?,
    val isolation: GcsIsolation,
    val contributions: ExtensionContributions,
)

object ExtensionDetailParser {

    /** Parse the agent's detail body; null when the body lacks a plugin id. */
    fun parse(body: JsonElement?): ExtensionDetail? {
        val root = body.asObject() ?: return null
        val install = root["install"].asObject()
        val manifest = root["manifest"].asObject()
        val pluginId = install?.get("plugin_id").str() ?: manifest?.get("id").str() ?: return null
        val gcs = manifest?.get("gcs").asObject()
        val grants = install?.get("permissions").asObject()?.map { (id, g) ->
            PermissionGrant(id, g.asObject()?.get("granted").bool() == true)
        }.orEmpty()
        val declared = manifest?.get("permissions").asArray().orEmpty().mapNotNull { raw ->
            val p = raw.asObject() ?: return@mapNotNull null
            val id = p["id"].str() ?: return@mapNotNull null
            DeclaredPermission(id, p["required"].bool() == true, p["label"].str(), p["description"].str(), p["risk"].str())
        }
        val granted = root["granted_capabilities"].asArray()?.mapNotNull { it.str() }?.toSet()
            ?: grants.filter { it.granted }.map { it.id }.toSet()
        return ExtensionDetail(
            pluginId = pluginId,
            name = manifest?.get("name").str() ?: pluginId,
            version = manifest?.get("version").str() ?: install?.get("version").str().orEmpty(),
            risk = manifest?.get("risk").str(),
            status = InstallStatus.fromWire(install?.get("status").str()),
            permissions = grants,
            declaredPermissions = declared,
            grantedCapabilities = granted,
            entrypoint = gcs?.get("entrypoint").str(),
            isolation = if (gcs?.get("isolation").str() == "inline") GcsIsolation.Inline else GcsIsolation.Iframe,
            contributions = ContributionParser.parse(gcs?.get("contributes")),
        )
    }
}

/** One entry of the agent's bundled first-party catalog. */
data class CatalogEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val risk: String?,
    val downloadUrl: String?,
    val archiveSha256: String?,
)

object CatalogParser {
    fun parse(body: JsonElement?): List<CatalogEntry> =
        body.asObject()?.get("plugins").asArray().orEmpty().mapNotNull { raw ->
            val e = raw.asObject() ?: return@mapNotNull null
            val id = e["id"].str() ?: return@mapNotNull null
            CatalogEntry(
                id = id,
                name = e["name"].str() ?: id,
                version = e["version"].str().orEmpty(),
                description = e["description"].str(),
                risk = e["risk"].str(),
                downloadUrl = e["download_url"].str(),
                archiveSha256 = e["archive_sha256"].str(),
            )
        }
}
