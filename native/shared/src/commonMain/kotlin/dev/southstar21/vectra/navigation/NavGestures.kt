package dev.southstar21.vectra.navigation

import kotlin.math.abs

/**
 * Pure tap/swipe classification shared by the
 * bottom nav and the schedule day switcher.
 */
object NavGestures {
    /** A touch with less movement than this (either axis) is a tap. */
    const val TAP_SLOP = 20f
    /** A horizontal drag must move at least this far to count as a swipe. */
    const val MIN_SWIPE_DISTANCE = 50f
    private const val VERTICAL_DRAG_RATIO = 1.2f

    data class Point(val x: Float, val y: Float)

    sealed interface Result {
        data class Tap(val index: Int?) : Result
        data class Swipe(val index: Int) : Result
        data object None : Result
    }

    fun isTap(start: Point, end: Point) = abs(end.x - start.x) < TAP_SLOP && abs(end.y - start.y) < TAP_SLOP

    /** +1 for a right swipe (next), −1 for a left swipe (previous), 0 otherwise. */
    fun swipeStep(start: Point, end: Point): Int {
        val dx = end.x - start.x
        val dy = end.y - start.y
        if (abs(dx) < MIN_SWIPE_DISTANCE || abs(dx) < abs(dy) * VERTICAL_DRAG_RATIO) return 0
        return if (dx > 0) 1 else -1
    }

    /** Taps target the tapped tab; swipes cycle the tabs with wrap-around. */
    fun resolve(start: Point, end: Point, tappedIndex: Int?, currentIndex: Int, count: Int): Result {
        if (isTap(start, end)) return Result.Tap(tappedIndex)
        val step = swipeStep(start, end)
        if (step == 0 || currentIndex < 0) return Result.None
        return Result.Swipe((currentIndex + step + count) % count)
    }
}
