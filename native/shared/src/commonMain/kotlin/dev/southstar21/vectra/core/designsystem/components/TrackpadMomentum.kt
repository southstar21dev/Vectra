package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScrollModifierNode
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** No scroll for this long and no fling: the fingers have left the trackpad. */
private const val QuietMs = 50L

/** Slower than this (px/s) the scroll just stops, as a slow touch drag does. */
private const val MinGlideVelocity = 300f

/**
 * Momentum for a trackpad scroll where the platform gives none ([trackpadScrollStopsDead]): there
 * Compose gets the scroll as plain deltas and nothing once the fingers lift, so a page stops dead
 * instead of gliding as it does everywhere else on a Mac.
 *
 * It watches the container's scrolls through nested scroll. A touch drag always ends in a fling; a
 * trackpad scroll just stops. When the scrolls stop with no fling, the container glides on at the
 * speed they had, with the same spline decay as a touch fling. A new scroll or a click stops it.
 *
 * Goes right before the container's `verticalScroll` / `LazyColumn` modifier, with the same [state].
 */
fun Modifier.trackpadMomentum(state: ScrollableState, orientation: Orientation = Orientation.Vertical): Modifier =
    if (trackpadScrollStopsDead) this then TrackpadMomentumElement(state, orientation) else this

private data class TrackpadMomentumElement(
    val state: ScrollableState,
    val orientation: Orientation,
) : ModifierNodeElement<TrackpadMomentumNode>() {
    override fun create() = TrackpadMomentumNode(state, orientation)

    override fun update(node: TrackpadMomentumNode) {
        node.state = state
        node.orientation = orientation
        node.stop()
    }
}

private class TrackpadMomentumNode(var state: ScrollableState, var orientation: Orientation) : DelegatingNode() {
    private val clock = TimeSource.Monotonic.markNow()
    private val tracker = VelocityTracker1D(isDataDifferential = true)
    private var samples = 0
    private var ending: Job? = null
    private var glide: Job? = null

    private val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) {
                glide?.cancel()
                tracker.addDataPoint(clock.elapsedNow().inWholeMilliseconds, if (orientation == Orientation.Vertical) consumed.y else consumed.x)
                samples++
                ending?.cancel()
                ending = coroutineScope.launch {
                    delay(QuietMs)
                    startGlide()
                }
            }
            return Offset.Zero
        }

        // A touch drag: the container flings itself.
        override suspend fun onPreFling(available: Velocity): Velocity {
            stop()
            return Velocity.Zero
        }
    }

    init {
        delegate(nestedScrollModifierNode(connection, null))
        // A click stops the glide, as on a Mac.
        delegate(
            SuspendingPointerInputModifierNode {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) stop()
                    }
                }
            },
        )
    }

    fun stop() {
        ending?.cancel()
        glide?.cancel()
        tracker.resetTracking()
        samples = 0
    }

    private fun startGlide() {
        val velocity = if (samples >= 2) tracker.calculateVelocity() else 0f
        tracker.resetTracking()
        samples = 0
        if (abs(velocity) < MinGlideVelocity) return
        val decay = splineBasedDecay<Float>(requireDensity())
        val target = state
        glide = coroutineScope.launch {
            target.scroll {
                var last = 0f
                AnimationState(0f, velocity).animateDecay(decay) {
                    // The deltas are in offset space; scrolling down moves the content up.
                    val delta = value - last
                    last = value
                    val used = -scrollBy(-delta)
                    if (abs(delta - used) > 0.5f) cancelAnimation() // reached an end
                }
            }
        }
    }
}
