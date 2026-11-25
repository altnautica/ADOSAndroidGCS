package com.altnautica.gcs.feature.configure.params

import com.altnautica.gcs.core.model.ParamDoc
import com.altnautica.gcs.core.model.ParamDocBook
import com.altnautica.gcs.core.model.ParamDocs
import com.altnautica.gcs.feature.configure.common.fcVariantOf
import javax.inject.Inject

/** Parameter documentation from the bundled metadata files the parameter editor reads. */
class ParamDocsFromMetadata @Inject constructor(private val repo: ParamMetadataRepository) : ParamDocs {
    override suspend fun book(autopilot: Int, vehicleType: Int): ParamDocBook? {
        val key = ParamMetadataKey.forVehicle(fcVariantOf(autopilot), vehicleType) ?: return null
        val set = repo.load(key) ?: return null
        return ParamDocBook { name -> set[name]?.toDoc() }
    }
}

internal fun ParamMeta.toDoc(): ParamDoc = ParamDoc(
    name = name,
    title = humanName,
    description = description,
    units = units,
    options = options.associate { it.value to it.label },
    bits = bits.associate { it.bit to it.label },
    rebootRequired = rebootRequired,
)
