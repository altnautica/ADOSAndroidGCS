package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.core.skills.spec.DirectorySpecSource
import java.io.File

/** The cockpit spec the app ships, read from the cockpit module's assets (tests run in the module directory). */
val testSpec = CockpitSpecRepository(DirectorySpecSource(File("../../feature/cockpit/src/main/assets/cockpit-spec")))
