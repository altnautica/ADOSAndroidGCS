package com.altnautica.gcs.feature.cockpit.launch

import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotActionResult
import com.altnautica.gcs.core.model.PilotLink
import com.altnautica.gcs.core.skills.NoticeLevel
import com.altnautica.gcs.core.skills.SkillNotice
import com.altnautica.gcs.core.skills.builtin.BuiltinIds
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.core.skills.hud.refusalText
import com.altnautica.gcs.core.skills.spec.SetupStringsSpec

/**
 * A launch's Abort: the pilot's `pause`, sent straight on [link]. It never goes through the
 * Pause skill, whose arm gate refuses while the launch is still in its checks or arm step;
 * the pilot itself refuses it where a pause must not run (a failsafe).
 */
internal suspend fun sendLaunchAbort(link: PilotLink?): PilotActionResult =
    link?.action(PilotAction(PilotAction.PAUSE)) ?: PilotActionResult.Unavailable

/** The notice for an Abort the pilot refused: `flow.launch.abort_refused` with the refusal's sentence. */
internal fun abortRefusedNotice(code: String?, strings: SetupStringsSpec, text: SpecText): SkillNotice = SkillNotice(
    skillId = BuiltinIds.PAUSE,
    level = NoticeLevel.Warning,
    reasonKey = null,
    message = strings.format("flow.launch.abort_refused", mapOf("reason" to text.refusalText(code))),
)
