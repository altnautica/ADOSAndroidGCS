package com.altnautica.gcs.core.skills.spec

import kotlinx.serialization.Serializable

/**
 * The alert list's sections (`alert-groups.json`): a readiness item groups by its readiness id,
 * any other alert by its id, then by its pill source; [order] lists the sections and [titles]
 * names them.
 */
@Serializable
data class AlertGroupsSpec(
    val v: Int,
    val readiness: Map<String, String> = emptyMap(),
    val id: Map<String, String> = emptyMap(),
    val source: Map<String, String> = emptyMap(),
    val order: List<String>,
    val titles: Map<String, String> = emptyMap(),
)
