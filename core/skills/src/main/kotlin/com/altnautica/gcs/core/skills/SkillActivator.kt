package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.ArmRequirement
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.model.ConfirmPolicy
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillState
import com.altnautica.gcs.core.skills.builtin.ArgumentConfirm
import com.altnautica.gcs.core.skills.builtin.SkillArgs
import com.altnautica.gcs.core.skills.builtin.disabledState
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** Monotonic milliseconds on the `SystemClock.elapsedRealtime()` time base. */
fun interface MonotonicClock {
    fun nowMs(): Long
}

/** What the confirm surface is asked to show. */
data class ConfirmRequest(
    val vehicleId: DeviceId,
    val skill: Skill,
    val policy: ConfirmPolicy,
    val args: Map<String, Any?>,
    /** Gamepad button whose hold satisfies the confirm (the one that triggered the skill). */
    val gamepadButton: Int?,
    val checklistReady: Boolean,
)

sealed interface ConfirmResult {
    data object Cancelled : ConfirmResult

    /** [args] are merged over the activation arguments (the takeoff altitude stepper, for example). */
    data class Confirmed(
        val args: Map<String, Any?> = emptyMap(),
        val checklistOverridden: Boolean = false,
    ) : ConfirmResult
}

/** The operator-facing confirm surface; suspends until the operator confirms or dismisses. */
fun interface SkillConfirmer {
    suspend fun confirm(request: ConfirmRequest): ConfirmResult
}

sealed interface ActivationOutcome {
    /** No vehicle or no skill. */
    data object Missing : ActivationOutcome

    /** An activation or confirm for the same vehicle and skill is already in flight. */
    data object Busy : ActivationOutcome

    data class Disabled(val reasonKey: String?) : ActivationOutcome

    /** An active toggle was pressed again and stopped. */
    data class Deactivated(val result: CommandResult) : ActivationOutcome

    /** Arm requirement or charge budget refused the press before any confirm. */
    data class Refused(val reasonKey: String) : ActivationOutcome

    data object Cancelled : ActivationOutcome

    /** A repeat one-shot inside the debounce window. */
    data object Debounced : ActivationOutcome

    data class Completed(val result: CommandResult, val checklistOverridden: Boolean) : ActivationOutcome

    data class Failed(val message: String) : ActivationOutcome
}

/** True when the command was taken by the vehicle or queued for it. */
fun CommandResult.succeeded(): Boolean = this == CommandResult.Accepted || this is CommandResult.Queued

/**
 * The single activation pipeline for touch, keyboard and gamepad. Gate order:
 * presence, busy, disabled, toggle-off, arm requirement, charges, confirm,
 * one-shot debounce, behaviour pre-empt, activate.
 */
@Singleton
class SkillActivator @Inject constructor(
    private val clock: MonotonicClock,
) {
    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val debounceUntil = ConcurrentHashMap<String, Long>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    private fun key(vehicle: DeviceId, skillId: String) = "${vehicle.value}::$skillId"

    /** End of the cooldown window started by the last accepted activation, or null when none is running. */
    fun cooldownEndsAt(vehicle: DeviceId, skillId: String): Long? {
        val k = key(vehicle, skillId)
        val until = cooldownUntil[k] ?: return null
        if (until <= clock.nowMs()) {
            cooldownUntil.remove(k, until)
            return null
        }
        return until
    }

    /** The skill's computed state with the dispatcher's cooldown window merged in. */
    fun mergedState(vehicle: DeviceId?, skill: Skill, ctx: SkillContext): SkillState {
        val computed = try {
            skill.state(ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            disabledState(SkillReason.STATE_ERROR)
        }
        if (vehicle == null || !computed.enabled || computed.active) return computed
        val until = cooldownEndsAt(vehicle, skill.id) ?: return computed
        return computed.copy(cooldownEndsAtMs = until)
    }

    suspend fun activate(
        skill: Skill?,
        ctx: SkillContext,
        args: Map<String, Any?>,
        confirmer: SkillConfirmer,
        peers: List<Skill> = emptyList(),
    ): ActivationOutcome {
        // 1. Presence.
        val vehicleId = ctx.vehicle?.id ?: return ActivationOutcome.Missing
        if (skill == null) return ActivationOutcome.Missing
        val k = key(vehicleId, skill.id)

        // 2. Busy: an in-flight activation or open confirm drops further presses.
        if (k in busy) return ActivationOutcome.Busy

        // 3. Disabled gate: report the reason, never open a confirm.
        val state = mergedState(vehicleId, skill, ctx)
        if (!state.enabled) return ActivationOutcome.Disabled(state.reasonKey)

        // 4. Toggle-off: pressing an active toggle stops it.
        if (skill.toggle && state.active) return deactivate(vehicleId, skill, ctx)

        // 5. Arm requirement; an unknown arm state is reported as the missing link.
        val armReason = armRefusal(skill.armRequirement, ctx.armed)
        if (armReason != null) return ActivationOutcome.Refused(armReason)

        // 6. Charges.
        if (!skill.toggle && state.charges == 0) return ActivationOutcome.Refused(SkillReason.NO_CHARGES)

        // 7. Confirm.
        var activateArgs = args
        var overridden = false
        val policy = (skill as? ArgumentConfirm)?.confirmFor(args) ?: skill.confirm
        if (policy != null && policy.gesture != ConfirmGesture.Tap) {
            if (!busy.add(k)) return ActivationOutcome.Busy
            val result = try {
                confirmer.confirm(
                    ConfirmRequest(
                        vehicleId = vehicleId,
                        skill = skill,
                        policy = policy,
                        args = args,
                        gamepadButton = args[SkillArgs.GAMEPAD_BUTTON] as? Int,
                        checklistReady = ctx.checklistReady,
                    ),
                )
            } finally {
                busy.remove(k)
            }
            when (result) {
                ConfirmResult.Cancelled -> return ActivationOutcome.Cancelled
                is ConfirmResult.Confirmed -> {
                    activateArgs = args + result.args
                    overridden = result.checklistOverridden
                }
            }
        }

        // 8. One-shot debounce.
        if (!skill.toggle) {
            val now = clock.nowMs()
            val until = debounceUntil[k] ?: 0L
            if (now < until) return ActivationOutcome.Debounced
            debounceUntil[k] = now + SkillTiming.DEBOUNCE_MS
        }

        // 9. A running behaviour (follow-me and the like) ends before a skill that moves the vehicle.
        if (preemptsBehaviors(skill.id)) {
            for (peer in peers) {
                if (peer.id == skill.id || !peer.toggle || peer.category != SkillCategory.Behavior) continue
                val peerState = runCatching { peer.state(ctx) }.getOrNull() ?: continue
                if (peerState.active) deactivate(vehicleId, peer, ctx)
            }
        }

        // 10. Activate.
        if (!busy.add(k)) return ActivationOutcome.Busy
        return try {
            val result = skill.activate(ctx, activateArgs)
            if (!skill.toggle && result.succeeded() && skill.cooldownMs > 0) {
                cooldownUntil[k] = clock.nowMs() + skill.cooldownMs
            }
            ActivationOutcome.Completed(result, overridden)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActivationOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            busy.remove(k)
        }
    }

    private suspend fun deactivate(vehicleId: DeviceId, skill: Skill, ctx: SkillContext): ActivationOutcome {
        val k = key(vehicleId, skill.id)
        if (!busy.add(k)) return ActivationOutcome.Busy
        return try {
            ActivationOutcome.Deactivated(skill.deactivate(ctx))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActivationOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            busy.remove(k)
        }
    }

    companion object {
        private val PREEMPTING = setOf("rth", "land", "abort", "kill", "set-mode", "pause")

        fun preemptsBehaviors(skillId: String) = skillId in PREEMPTING || skillId.startsWith("mode.")

        fun armRefusal(requirement: ArmRequirement, armed: Boolean?): String? = when {
            requirement == ArmRequirement.Any -> null
            armed == null -> SkillReason.NO_FC_LINK
            requirement == ArmRequirement.Armed && !armed -> SkillReason.NOT_ARMED
            requirement == ArmRequirement.Disarmed && armed -> SkillReason.ALREADY_ARMED
            else -> null
        }
    }
}
