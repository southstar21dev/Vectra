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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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
 * The pushed screen sits in a card in the middle of a wide window, away from the system bars,
 * so its [ScreenColumn] doesn't pad them. Set by the app shell around the screen.
 */
@Suppress("CompositionLocalAllowlist")
val LocalScreenInCard = staticCompositionLocalOf { false }

/**
 * Vertical offset of a draggable sheet, measured down from its fully expanded position.
 * [anchors] are the resting offsets (ascending); the last one is fully hidden.
 */
@Stable
class SheetDragState internal constructor(private val haptics: VectraHaptics) {
    internal val offset = Animatable(Float.NaN)
    private var anchorList by mutableStateOf(emptyList<Float>())
    internal var anchors: List<Float>
        get() = anchorList
        set(value) {
            anchorList = value
            // Never above the top anchor: a fast fling's spring overshoot (or a pull past the top)
            // would lift the panel off the window's bottom edge and show the page under it.
            offset.updateBounds(lowerBound = value.firstOrNull())
        }
    var dragging by mutableStateOf(false)
        internal set

    val hidden: Float get() = anchors.lastOrNull() ?: 0f
    val current: Float get() = offset.value.takeUnless { it.isNaN() } ?: hidden
    /** 1 when fully open, 0 when hidden. */
    val openFraction: Float get() = if (hidden <= 0f) 0f else (1f - current / hidden).coerceIn(0f, 1f)

    /** An expandable sheet's climb from its half-height rest (0, or anywhere lower) to fully open (1). */
    val expansion: Float get() {
        val half = anchors.takeIf { it.size == 3 }?.get(1)?.takeIf { it > 0f } ?: return 1f
        return (1f - current / half).coerceIn(0f, 1f)
    }

    /** Moves by [delta] px, stopping at the top anchor. Returns what was consumed. */
    internal suspend fun dragBy(delta: Float): Float {
        if (anchors.isEmpty()) return 0f
        offset.snapTo((current + delta).coerceIn(anchors.first(), hidden))
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

    /** The resting height the sheet is at (or nearest to). */
    internal fun nearestIndex(): Int = anchors.indexOf(nearest(current))

    internal suspend fun animateTo(target: Float, spec: AnimationSpec<Float>) {
        offset.animateTo(target, spec)
    }

    internal suspend fun snapTo(target: Float) = offset.snapTo(target)

    private companion object {
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
    /**
     * Floats at its half-height rest: inset from the window's sides and bottom with every corner
     * rounded, settling onto the edges as it is pulled fully open (iOS 26 sheets). Glass surface.
     */
    floating: Boolean = false,
    /** [SheetSize.Expandable]: the share of the window the sheet covers at its half-height rest. */
    halfFraction: Float = 0.5f,
    /**
     * Held fully open and still (content that takes the whole sheet and its touches, such as a
     * map grown to fill it); when released the sheet returns to the height it had before.
     */
    pinnedOpen: Boolean = false,
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

    val restoreTo = remember { intArrayOf(-1) }
    LaunchedEffect(pinnedOpen, state.anchors.isNotEmpty()) {
        if (state.anchors.isEmpty() || !currentOpen) return@LaunchedEffect
        if (pinnedOpen) {
            if (restoreTo[0] < 0) restoreTo[0] = state.nearestIndex()
            state.animateTo(state.anchors.first(), spec)
        } else if (restoreTo[0] >= 0) {
            val to = state.anchors.getOrNull(restoreTo[0])
            restoreTo[0] = -1
            if (to != null && to != state.hidden) state.animateTo(to, spec)
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
                        val half = (s.height - windowHeight * halfFraction).coerceAtLeast(0f)
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
                    .then(
                        if (floating) Modifier.floatingSheetSurface(state, c.glassBgElevated, c.glassBorder, c.glassBorderSpecular)
                        else Modifier.boxShadow(shape, cardDepthShadows(c.isDark)).background(c.surfaceSecondary, shape),
                    )
                    .consumeTouches()
                    .draggable(
                        rememberDraggableState { delta -> scope.launch { state.dragBy(delta) } },
                        Orientation.Vertical,
                        enabled = open && !pinnedOpen,
                        onDragStarted = { state.dragging = true },
                        onDragStopped = { velocity -> release(velocity) },
                    )
                    .then(if (open && !pinnedOpen) Modifier.nestedScroll(nested) else Modifier)
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
                    // A floating sheet redraws its outline every frame of a pull: its content gets
                    // a layer of its own, replayed instead of repainted (a full hub per frame lagged
                    // on older iPhones).
                    .then(if (floating) Modifier.graphicsLayer() else Modifier),
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
 * A sheet's grab handle for content drawn edge to edge (a map at the top): pass
 * `dragHandle = false` and place this over the content. Drags on it move the sheet, not what is under it.
 */
@Composable
fun SheetGrabber(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 120.dp, height = 24.dp)
            // A hit target of its own, so the drag reaches the sheet instead of the map under it.
            .pointerInput(Unit) {},
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.padding(top = 7.dp).size(36.dp, 5.dp).background(Color.White.copy(alpha = 0.55f), CircleShape))
    }
}

/** How far a floating sheet sits in from the window's sides and bottom at its half-height rest. */
private val FloatingInset = 8.dp
private val FloatingCorner = 32.dp

/** The visible part of a floating sheet: inset and fully rounded at half height, square to the window's bottom when open. */
private class FloatingSheetShape(
    private val inset: Float,
    private val visibleHeight: Float,
    private val topCorner: Float,
    private val bottomCorner: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(
            RoundRect(
                left = inset, top = 0f, right = size.width - inset, bottom = visibleHeight.coerceIn(0f, size.height),
                topLeftCornerRadius = CornerRadius(topCorner), topRightCornerRadius = CornerRadius(topCorner),
                bottomRightCornerRadius = CornerRadius(bottomCorner), bottomLeftCornerRadius = CornerRadius(bottomCorner),
            ),
        )
}

private fun Density.floatingShape(size: Size, state: SheetDragState): FloatingSheetShape {
    val rest = 1f - state.expansion
    val inset = FloatingInset.toPx() * rest
    // The window's bottom edge, in the panel's own coordinates, lifted by the inset.
    val visible = size.height - state.current - inset
    return FloatingSheetShape(inset, visible, FloatingCorner.toPx(), FloatingCorner.toPx() * rest)
}

/** Glass fill, clip, shadow and a specular top edge that all follow [floatingShape]. */
private fun Modifier.floatingSheetSurface(state: SheetDragState, fill: Color, side: Color, top: Color): Modifier =
    graphicsLayer {
        shape = floatingShape(size, state)
        clip = true
        shadowElevation = 24.dp.toPx()
        ambientShadowColor = Color.Black
        spotShadowColor = Color.Black
    }.drawWithContent {
        val outline = floatingShape(size, state).createOutline(size, layoutDirection, this)
        drawOutline(outline, fill)
        drawContent()
        drawOutline(
            outline,
            Brush.verticalGradient(0f to top, 0.5f to side, 1f to side, startY = 0f, endY = 48.dp.toPx()),
            style = Stroke(1.dp.toPx()),
        )
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
