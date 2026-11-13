package com.altnautica.gcs.feature.configure.wizard

import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.agent.fcsetup.SetupWizard
import com.altnautica.gcs.core.agent.fcsetup.SetupWizardState

/** Steps of [wizard] that failed. A wizard the agent marks done still failed when any step did. */
internal fun failedSteps(wizard: SetupWizard?): Int = wizard?.steps?.count { it.state == SetupStepState.Failed } ?: 0

/** True only when the wizard finished and every step is done or skipped. */
internal fun wizardSucceeded(wizard: SetupWizard?): Boolean =
    wizard != null && wizard.state == SetupWizardState.Done && failedSteps(wizard) == 0
