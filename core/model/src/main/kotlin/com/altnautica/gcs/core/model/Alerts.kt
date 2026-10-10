package com.altnautica.gcs.core.model

enum class AlertLevel { Critical, Warning, Advisory }

data class CockpitAlert(
    val id: String,
    val level: AlertLevel,
    val titleKey: String,
    val detail: String?,
    val sinceMs: Long,
)
