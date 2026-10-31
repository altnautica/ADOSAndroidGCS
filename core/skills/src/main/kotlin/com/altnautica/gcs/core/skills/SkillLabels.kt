package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillSource

private val LABELS = mapOf(
    "skills.arm" to R.string.skill_label_arm,
    "skills.disarm" to R.string.skill_label_disarm,
    "skills.takeoff" to R.string.skill_label_takeoff,
    "skills.land" to R.string.skill_label_land,
    "skills.rth" to R.string.skill_label_rth,
    "skills.pause" to R.string.skill_label_pause,
    "skills.resume" to R.string.skill_label_resume,
    "skills.abort" to R.string.skill_label_abort,
    "skills.kill" to R.string.skill_label_kill,
    "skills.modeLoiter" to R.string.skill_label_mode_loiter,
    "skills.modeBrake" to R.string.skill_label_mode_brake,
    "skills.modeAltHold" to R.string.skill_label_mode_althold,
    "skills.modeStabilize" to R.string.skill_label_mode_stabilize,
    "skills.modeGuided" to R.string.skill_label_mode_guided,
    "skills.modeAuto" to R.string.skill_label_mode_auto,
    "skills.setMode" to R.string.skill_label_set_mode,
    "skills.flyHere" to R.string.skill_label_fly_here,
)

/**
 * The localized label resource of a built-in skill, or null for an extension skill,
 * whose label is shown as the extension provides it ([Skill.labelKey]).
 */
fun Skill.builtinLabelRes(): Int? = if (source is SkillSource.Builtin) LABELS[labelKey] else null
