package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.VectraHaptics
import dev.southstar21.vectra.core.designsystem.theme.VectraSprings
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.cardDepthShadows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import dev.southstar21.vectra.core.designsystem.theme.consumeTouches

/** Corner radius of a sheet's top edge. */
val SheetCornerRadius = 24.dp

/** How tall a sheet may grow. */
enum class SheetSize {
    /** As tall as its content, up to just below the status bar. */
    Fit,
    /** Opens at half the window and drags up to full height (long lists, maps). */
    Expandable,
    /** Always full height. */
    Full,
}

/** Whether the surrounding surface runs under the system bars and must pad them itself. */
val LocalSurfaceEdgeToEdge = staticCompositionLocalOf { false }

/**
 * Vertical offset of a draggable sheet, measured down from its fully expanded position.
 * [anchors] are the resting offsets (ascending); the last one is fully hidden.
 */
@Stable
class SheetDragState internal constructor(private val haptics: VectraHaptics) {
    internal val offset = Animatable(Float.NaN)
    internal var anchors by mutableStateOf(emptyList<Float>())
    var dragging by mutableStateOf(false)
        internal set

    val hidden: Float get() = anchors.lastOrNull() ?: 0f
    val current: Float get() = offset.value.takeUnless { it.isNaN() } ?: hidden
    /** 1 when fully open, 0 when hidden. */
    val openFraction: Float get() = if (hidden <= 0f) 0f else (1f - current / hidden).coerceIn(0f, 1f)

    /** Moves by [delta] px; pulling above the top anchor is resisted. Returns what was consumed. */
    internal suspend fun dragBy(delta: Float): Float {
        if (anchors.isEmpty()) return 0f
        val from = current
        val top = anchors.first()
        val raw = from + delta
        val next = if (raw < top) top + (raw - top) * Resistance else raw
        offset.snapTo(next.coerceAtMost(hidden))
        return delta
    }

    /** Settles on the anchor the gesture is heading for. Returns it. */
    internal suspend fun settle(velocity: Float, spec: AnimationSpec<Float>): Float {
        val target = targetFor(current, velocity)
        if (target != hidden && target != nearest(current)) haptics.tick()
        offset.animateTo(target, spec, initialVelocity = velocity)
        return target
    }

    internal fun targetFor(position: Float, velocity: Float): Float {
        if (anchors.isEmpty()) return position
        return when {
            velocity > FlingVelocity -> anchors.firstOrNull { it > position + 1f } ?: hidden
            velocity < -FlingVelocity -> anchors.lastOrNull { it < position - 1f } ?: anchors.first()
            else -> nearest(position)
        }
    }

    private fun nearest(position: Float) = anchors.minBy { abs(it - position) }

    internal suspend fun animateTo(target: Float, spec: AnimationSpec<Float>) {
        offset.animateTo(target, spec)
    }

    internal suspend fun snapTo(target: Float) = offset.snapTo(target)

    private companion object {
        const val Resistance = 0.2f
        const val FlingVelocity = 1200f
    }
}

/**
 * Hands vertical drags inside the sheet between its content and the sheet itself: content
 * scrolls first, and once it is back at its top a further pull moves the sheet down.
 */
private class SheetNestedScroll(
    private val state: SheetDragState,
    private val scope: CoroutineScope,
    private val onSettle: (Float) -> Unit,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val dy = available.y
        // Pulling up while the sheet is below its top anchor raises the sheet first.
        return if (dy < 0f && source == NestedScrollSource.UserInput && state.current > (state.anchors.firstOrNull() ?: 0f)) {
            scope.launch { state.dragBy(dy) }
            Offset(0f, dy)
        } else Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val dy = available.y
        return if (dy > 0f && source == NestedScrollSource.UserInput) {
            state.dragging = true
            scope.launch { state.dragBy(dy) }
            Offset(0f, dy)
        } else Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val top = state.anchors.firstOrNull() ?: return Velocity.Zero
        return if (state.current > top + 0.5f) {
            onSettle(available.y)
            available
        } else Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (state.dragging) onSettle(available.y)
        return available
    }
}

/**
 * Modal bottom sheet. Drags and flings between its resting heights, hands off to scrolling
 * content, follows the predictive back gesture, and stays above the keyboard.
 *
 * Dragging it away, tapping the scrim or going back calls [onDismiss]; if the caller keeps
 * [open] true (say, to confirm discarding a draft) the sheet springs back.
 *
 * For screen readers the sheet is a pane named [title] (announced as it opens) with a dismiss
 * action, and the page behind it leaves the accessibility tree while it is up.
 */
@Composable
fun VSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    size: SheetSize = SheetSize.Fit,
    dragHandle: Boolean = true,
    maxWidth: Dp = 640.dp,
    title: String? = null,
    onExited: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var mounted by remember { mutableStateOf(open) }
    if (open) mounted = true
    if (!mounted) return

    val haptics = Vectra.haptics
    val state = remember { SheetDragState(haptics) }
    val scope = rememberCoroutineScope()
    val reduceMotion = Vectra.reduceMotion
    val currentOpen by rememberUpdatedState(open)
    val dismiss by rememberUpdatedState(onDismiss)
    val exited by rememberUpdatedState(onExited)
    val spec: AnimationSpec<Float> = if (reduceMotion) tween(200, easing = VectraEasing.Fade) else VectraSprings.sheet()

    // While closing, dragging and nested scroll are off (see the panel's modifiers): a drag would
    // cancel the exit animation and strand the sheet open, with the scrim and back already off.
    fun finishExit() {
        if (!mounted) return
        mounted = false
        exited?.invoke()
    }

    fun release(velocity: Float) {
        scope.launch {
            state.dragging = false
            // A drag that interrupted the exit animation cancelled it: finish closing from here.
            if (!currentOpen) {
                state.animateTo(state.hidden, spec)
                finishExit()
                return@launch
            }
            val target = state.targetFor(state.current, velocity)
            if (target == state.hidden) {
                dismiss()
                withFrameNanos { }
                // The caller refused to close: come back to the nearest resting height.
                if (currentOpen) state.animateTo(state.anchors.first(), spec)
            } else {
                state.settle(velocity, spec)
            }
        }
    }

    // Open once the panel has measured (anchors known); close back down past the bottom edge.
    LaunchedEffect(open, state.anchors.isNotEmpty()) {
        if (state.anchors.isEmpty()) return@LaunchedEffect
        if (open) {
            if (state.offset.value.isNaN()) state.snapTo(state.hidden)
            // Expandable sheets open at half height; the rest open fully.
            state.animateTo(if (size == SheetSize.Expandable) state.anchors[state.anchors.size - 2] else 0f, spec)
        } else {
            state.animateTo(state.hidden, spec)
            finishExit()
        }
    }

    val back = rememberPredictiveBack(enabled = open) { dismiss() }
    val nested = remember(state) { SheetNestedScroll(state, scope, ::release) }

    Overlay {
        val c = Vectra.colors
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxSize().modalPane(title ?: "Sheet", open) { dismiss() }) {
            val windowHeight = constraints.maxHeight.toFloat()
            val topGap = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp
            val maxPanelHeight = maxHeight - topGap
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = state.openFraction }
                    .background(c.dialogScrim)
                    .consumeTouches(enabled = open) { dismiss() }
                    .scrimSemantics(enabled = open) { dismiss() },
            )
            var panelHeight by remember { mutableFloatStateOf(0f) }
            val shape = RoundedCornerShape(topStart = SheetCornerRadius, topEnd = SheetCornerRadius)
            Column(
                modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .heightIn(max = maxPanelHeight)
                    .then(if (size != SheetSize.Fit) Modifier.heightIn(min = maxPanelHeight) else Modifier)
                    .onSizeChanged { s ->
                        panelHeight = s.height.toFloat()
                        val hidden = s.height + with(density) { 24.dp.toPx() }
                        val half = (s.height - windowHeight * 0.5f).coerceAtLeast(0f)
                        state.anchors = if (size == SheetSize.Expandable && half > 0f) listOf(0f, half, hidden) else listOf(0f, hidden)
                    }
                    .offset { IntOffset(0, state.current.takeIf { panelHeight > 0f }?.roundToInt() ?: constraints.maxHeight) }
                    .graphicsLayer {
                        // Predictive back: the sheet shrinks a little and drops with the gesture.
                        val p = back()
                        val s = 1f - 0.06f * p
                        scaleX = s; scaleY = s
                        translationY = p * 32.dp.toPx()
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .boxShadow(shape, cardDepthShadows(c.isDark))
                    .background(c.surfaceSecondary, shape)
                    .consumeTouches()
                    .draggable(
                        rememberDraggableState { delta -> scope.launch { state.dragBy(delta) } },
                        Orientation.Vertical,
                        enabled = open,
                        onDragStarted = { state.dragging = true },
                        onDragStopped = { velocity -> release(velocity) },
                    )
                    .then(if (open) Modifier.nestedScroll(nested) else Modifier)
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
            ) {
                if (dragHandle) SheetHandle(Modifier.align(Alignment.CenterHorizontally))
                CompositionLocalProvider(LocalSurfaceEdgeToEdge provides false) { content() }
            }
        }
    }
}

@Composable
private fun SheetHandle(modifier: Modifier = Modifier) {
    Box(modifier.padding(top = 8.dp, bottom = 4.dp).size(36.dp, 5.dp).background(Vectra.colors.textMuted.copy(alpha = 0.4f), CircleShape))
}

/**
 * Registers a back handler that reports the predictive back gesture's progress (0 when idle),
 * so the surface can follow the finger before [onBack] commits.
 */
@Composable
fun rememberPredictiveBack(enabled: Boolean, onBack: () -> Unit): () -> Float {
    val latest = rememberUpdatedState(onBack)
    val navState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = navState,
        isBackEnabled = enabled,
        onBackCancelled = {},
        onBackCompleted = { latest.value() },
    )
    return {
        (navState.transitionState as? NavigationEventTransitionState.InProgress)?.latestEvent?.progress ?: 0f
    }
}

/** Small close button for a sheet header. */
@Composable
fun SheetCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    BarButton(RemixIcons.CloseLine, "Close", onClick, modifier)
}
