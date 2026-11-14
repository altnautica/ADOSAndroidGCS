package com.altnautica.gcs.feature.cockpit.hud

import com.altnautica.gcs.feature.cockpit.autonomy.FollowPhase
import com.altnautica.gcs.feature.cockpit.autonomy.FollowState

/**
 * What the local minimap draws of a fresh follow state: the target (with a 1 s velocity
 * tick), dashed in the coast colour while the lock is lost and the subject is sought, with the
 * search ellipse, the predicted subject path and the vantage point; nothing while the follow is
 * idle or holds for the operator.
 */
internal object MinimapFollow {
    /** Whether [f]'s target draws at all. */
    fun shows(f: FollowState): Boolean = f.targetNed != null && f.phase != FollowPhase.Idle && f.phase != FollowPhase.HoldingAwaitOperator

    /** Whether the target is drawn dashed, with its recovery geometry. */
    fun recovering(f: FollowState): Boolean = shows(f) && f.phase.recovering

    /**
     * The search ellipse's turn, degrees clockwise from screen right, on the north-up map: a
     * bearing of 90° (east) is screen right, so the major axis turns `heading − 90°`.
     */
    fun ellipseRotationDeg(headingDeg: Float): Float = headingDeg - 90f
}
