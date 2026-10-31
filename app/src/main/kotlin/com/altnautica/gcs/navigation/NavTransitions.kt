package com.altnautica.gcs.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import com.altnautica.gcs.core.designsystem.tokens.AdosMotion

private typealias SceneTransition = AnimatedContentTransitionScope<Scene<NavKey>>

/** Surface start scale on push (and end scale on pop): a slight zoom toward the viewer. */
private const val SURFACE_SCALE = 0.96f

/**
 * Route transitions: a push fades and scales the new surface in over the old one
 * fading out; a pop (back, or predictive back) reverses it. Reduced motion cuts.
 */
internal class NavTransitions(private val reducedMotion: Boolean) {

    private fun <T> spec(): FiniteAnimationSpec<T> = tween(AdosMotion.BaseMs, easing = AdosMotion.Emphasized)

    private fun none(): ContentTransform = EnterTransition.None togetherWith ExitTransition.None

    private fun push(): ContentTransform = if (reducedMotion) {
        none()
    } else {
        (fadeIn(spec()) + scaleIn(spec(), initialScale = SURFACE_SCALE)) togetherWith fadeOut(spec())
    }

    private fun pop(): ContentTransform = if (reducedMotion) {
        none()
    } else {
        fadeIn(spec()) togetherWith (fadeOut(spec()) + scaleOut(spec(), targetScale = SURFACE_SCALE))
    }

    val transitionSpec: SceneTransition.() -> ContentTransform = { push() }
    val popTransitionSpec: SceneTransition.() -> ContentTransform = { pop() }
    val predictivePopTransitionSpec: SceneTransition.(Int) -> ContentTransform = { pop() }
}
