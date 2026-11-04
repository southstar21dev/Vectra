package dev.southstar21.vectra.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/** Easing curves. Spatial movement prefers springs ([VectraSprings]); these are for tweens. */
object VectraEasing {
    /** Entrances: fast start, long soft landing. */
    val Decelerate = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    /** Something already on screen moving to a new place. */
    val Move = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
    /** Sheets, panes and pushed content. */
    val Sheet = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    /** Opacity and colour changes. */
    val Fade = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    /** Symmetric loops (breathing, bobbing). */
    val Breathe = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    /** Gentle general-purpose curve for small layout shifts. */
    val Soft = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
}

/**
 * Spring tokens. Springs keep velocity when a gesture hands off to an animation, so anything
 * the finger can touch (pills, sheets, rows, toggles) should settle with one of these.
 */
object VectraSprings {
    /** Selection pills, toggles, segmented controls. */
    fun <T> snappy(visibilityThreshold: T? = null): SpringSpec<T> = spring(0.85f, 500f, visibilityThreshold)
    /** Centered surfaces popping in (dialogs, menus). */
    fun <T> pop(visibilityThreshold: T? = null): SpringSpec<T> = spring(0.87f, 470f, visibilityThreshold)
    /** Large surfaces settling without overshoot. */
    fun <T> settle(visibilityThreshold: T? = null): SpringSpec<T> = spring(0.98f, 470f, visibilityThreshold)
    /** Snap-back after a drag (reorder rows, swipe actions). */
    fun <T> drag(visibilityThreshold: T? = null): SpringSpec<T> = spring(1f, 800f, visibilityThreshold)
    /** Sheet detents. */
    fun <T> sheet(visibilityThreshold: T? = null): SpringSpec<T> = spring(1f, 650f, visibilityThreshold)
    /** Slow indicators that follow live data. */
    fun <T> glide(visibilityThreshold: T? = null): SpringSpec<T> = spring(1.25f, 90f, visibilityThreshold)
}

object VectraMotion {
    /** Staggered card entrance on a page's first load. */
    const val RevealStaggerMs = 50
    const val RevealRiseDp = 14f
    const val RevealMaxStaggerSteps = 5
    fun <T> reveal(index: Int): FiniteAnimationSpec<T> = tween(
        durationMillis = 300,
        delayMillis = index.coerceIn(0, RevealMaxStaggerSteps) * RevealStaggerMs,
        easing = VectraEasing.Sheet,
    )

    /** What replaces movement when the system asks for reduced motion. */
    fun <T> reducedFade(): FiniteAnimationSpec<T> = tween(150, easing = VectraEasing.Fade)
}
