package dev.southstar21.vectra.features.schedule

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import dev.southstar21.vectra.navigation.NavGestures
import kotlin.math.abs

/** How much more horizontal than vertical a drag must be, as it crosses the touch slop, to be a day swipe. */
private const val DaySwipeAxisRatio = 1.5f

/**
 * Horizontal day swipe over NavGestures. The axis locks when the finger first crosses the touch slop,
 * before the page has scrolled: a vertical or diagonal start leaves the gesture to the scroll, a clearly
 * horizontal one is taken over (consumed) so the page doesn't scroll and the card under the finger
 * isn't clicked. Only a locked drag can switch the day; a second finger cancels it.
 *
 * [onStep] is a pointerInput key, so pass a stable (remembered) lambda or every recomposition
 * restarts the detector mid-gesture.
 */
internal fun Modifier.daySwipe(onStep: (Int) -> Unit): Modifier = pointerInput(onStep) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val step = trackDaySwipe(down, slop)
        if (step != 0) onStep(step)
    }
}

/** Follows one press from [down]; the day step it makes on release, or 0 when it wasn't a day swipe. */
private suspend fun AwaitPointerEventScope.trackDaySwipe(down: PointerInputChange, slop: Float): Int {
    var horizontal = false
    var step: Int? = null
    while (step == null) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id }
        step = when {
            // Lifted, lost or joined by a second finger: over.
            change == null || event.changes.count { it.pressed } > 1 -> 0
            !change.pressed -> if (horizontal) release(down, change) else 0
            horizontal -> null.also { change.consume() }
            change.isConsumed -> 0
            else -> when (horizontalLock(change.position - down.position, slop)) {
                null -> null
                false -> 0
                true -> null.also { horizontal = true; change.consume() }
            }
        }
    }
    return step
}

/** A locked swipe lifted at [up]: take the release and return its day step. */
private fun Density.release(down: PointerInputChange, up: PointerInputChange): Int {
    up.consume()
    return swipeStep(down.position, up.position)
}

/** The day step of a swipe from [start] to [end] (pixels). */
private fun Density.swipeStep(start: Offset, end: Offset): Int = NavGestures.swipeStep(
    NavGestures.Point(start.x / density, start.y / density),
    NavGestures.Point(end.x / density, end.y / density),
)

/** Null while the drag is under the touch slop; then whether it is clearly horizontal. */
private fun horizontalLock(delta: Offset, slop: Float): Boolean? = when {
    delta.getDistance() < slop -> null
    else -> abs(delta.x) > abs(delta.y) * DaySwipeAxisRatio
}
