package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillSource

/** Built-ins outside the shared cockpit table, labelled from this module's resources. */
private val LABELS = mapOf(
    "skills.setMode" to R.string.skill_label_set_mode,
    "skills.flyHere" to R.string.skill_label_fly_here,
    "skills.ekfSourceSet" to R.string.skill_label_ekf_source_set,
)

/**
 * The localized label resource of a built-in skill outside the shared cockpit table, or
 * null. Table built-ins and extension skills show [Skill.labelKey], which carries the
 * label text itself.
 */
fun Skill.builtinLabelRes(): Int? = if (source is SkillSource.Builtin) LABELS[labelKey] else null
