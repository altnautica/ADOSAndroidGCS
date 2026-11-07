package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.skills.builtin.BuiltinSkills
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.core.skills.spec.DirectorySpecSource
import java.io.File

/** The cockpit spec this module ships, read from its assets (tests run in the module directory). */
val testSpec = CockpitSpecRepository(DirectorySpecSource(File("src/main/assets/cockpit-spec")))

/** The spec's built-in skill [id]. */
fun testBuiltin(id: String): Skill = BuiltinSkills.from(testSpec.builtins, testSpec.ds4).first { it.id == id }
