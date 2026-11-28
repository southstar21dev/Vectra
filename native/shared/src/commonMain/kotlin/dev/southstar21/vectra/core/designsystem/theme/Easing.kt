package dev.southstar21.vectra.core.designsystem.theme

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/*
 * Pure easing and noise helpers for hand-drawn animation. One argument order for all of them:
 * edges first, the value last (as in GLSL), so a call can't silently swap range and input.
 */

/** Hermite smoothstep: 0 at or below [a], 1 at or above [b], smooth between. */
internal fun smoothstep(a: Float, b: Float, x: Float): Float {
    val u = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return u * u * (3 - 2 * u)
}

/** Cubic ease-in-out of [u] (0..1, not clamped). */
internal fun easeInOutCubic(u: Float): Float = if (u < 0.5f) 4 * u * u * u else 1 - (-2 * u + 2).pow(3) / 2

/** Ease-out that overshoots by [overshoot] before settling: a spring's feel in a fixed time. */
internal fun backOut(u: Float, overshoot: Float = 1.70158f): Float {
    if (u <= 0f) return 0f
    val v = u.coerceAtMost(1f) - 1
    return 1 + (overshoot + 1) * v.pow(3) + overshoot * v.pow(2)
}

/** The classic GLSL sine hash: a stable pseudo-random 0..1 from two numbers. */
internal fun sineHash(a: Double, b: Double): Double {
    val h = sin(a * 12.9898 + b * 78.233) * 43758.5453
    return h - floor(h)
}
