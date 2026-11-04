package dev.southstar21.vectra.features.transit

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.NativeSegmentedControl
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.hasNativeSegmentedControl
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.VectraSprings
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.vtext
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.isRenderEffectSupported
import dev.southstar21.vectra.features.navbar.liquid.LiquidTabs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

private const val SheetFraction = 0.72f
private const val PeekVisibleFraction = 0.34f

private data class ModeChip(val id: String, val label: String)

private val ModeChips = listOf(
    ModeChip("ALL", "All"),
    ModeChip("SUBWAY", "Metro"),
    ModeChip("TRAM", "Tram"),
    ModeChip("BUS", "Bus"),
    ModeChip("TROLLEYBUS", "Trolley"),
)

@Immutable
data class NearbySelectionKey(
    val stopId: String,
    val routeId: String,
    val headsign: String,
    val shortName: String,
    val color: String,
    val textColor: String,
)

/** The route colour at [alpha] (0–255). */
internal fun nearbyHexAlpha(color: String, alpha: Int): Color =
    (parseHexColor(color) ?: Color.Gray).copy(alpha = alpha / 255f)

/**
 * Rubber-band past the drag bounds: the overflow is scaled by the elastic factor.
 */
private fun elastic(raw: Float, min: Float, max: Float, factor: Float): Float = when {
    raw < min -> min + (raw - min) * factor
    raw > max -> max + (raw - max) * factor
    else -> raw
}

private enum class GestureMode { Drag, Pending, Pulling }

/**
 * The explorer's draggable departures sheet: two snap points (expanded and
 * peeked), the mode chips and the grouped departure feed.
 *
 * [onCoverageChange] reports how much of the container the sheet covers from
 * the bottom, in dp. `settled` is false for the per-frame updates during a drag
 * or spring — consumers should only do expensive work for settled values.
 */
@Composable
fun NearbyDeparturesSheet(
    feed: List<RadarDepartureItem>,
    selection: NearbySelectionKey?,
    onSelectItem: (RadarDepartureItem) -> Unit,
    modeFilter: List<String>,
    onToggleMode: (String) -> Unit,
    arrivalsEnabled: Boolean,
    isLoading: Boolean,
    /** Stops inside the map area the sheet leaves uncovered. */
    visibleStopCount: Int,
    /** Stops actually polled for departures (the arrivals cap). */
    polledStopCount: Int,
    modifier: Modifier = Modifier,
    onCoverageChange: ((coveredDp: Float, settled: Boolean) -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportHeight = maxHeight.value
        val expandedHeight = (viewportHeight * SheetFraction).roundToInt().toFloat()
        val peekOffset = (viewportHeight * (SheetFraction - PeekVisibleFraction)).roundToInt().toFloat()
        SheetBody(
            expandedHeight, peekOffset, feed, selection, onSelectItem, modeFilter, onToggleMode,
            arrivalsEnabled, isLoading, visibleStopCount, polledStopCount, onCoverageChange,
        )
    }
}

@Composable
private fun BoxScope.SheetBody(
    expandedHeight: Float,
    peekOffset: Float,
    feed: List<RadarDepartureItem>,
    selection: NearbySelectionKey?,
    onSelectItem: (RadarDepartureItem) -> Unit,
    modeFilter: List<String>,
    onToggleMode: (String) -> Unit,
    arrivalsEnabled: Boolean,
    isLoading: Boolean,
    visibleStopCount: Int,
    polledStopCount: Int,
    onCoverageChange: ((Float, Boolean) -> Unit)?,
) {
    val c = Vectra.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // Sheet offset from expanded, in dp. Opens pulled up (0). Plain state written straight from
    // the pointer loop, so a drag moves the sheet on the very frame the finger moves.
    val y = remember { mutableFloatStateOf(0f) }
    // Velocity of the running settle, so a grab mid-flight hands its speed on.
    val settleVelocity = remember { floatArrayOf(0f) }
    val settleJob = remember { arrayOfNulls<Job>(1) }
    val coverage by rememberUpdatedState(onCoverageChange)
    val latestPeek by rememberUpdatedState(peekOffset)
    val latestExpanded by rememberUpdatedState(expandedHeight)
    val suppressNextClick = remember { createClickSuppressor() }
    val feedScroll = rememberScrollState()

    fun reportCoverage(settled: Boolean) {
        coverage?.invoke((latestExpanded - y.floatValue).roundToInt().toFloat(), settled)
    }

    // Coverage on mount / size changes (settled) and on every frame (unsettled).
    LaunchedEffect(expandedHeight, peekOffset) {
        // Keep the sheet expanded on viewport changes (a reset to peek felt like a collapse).
        if (y.floatValue > peekOffset) y.floatValue = peekOffset
        reportCoverage(true)
    }
    LaunchedEffect(Unit) {
        var first = true
        snapshotFlow { y.floatValue }.collect {
            if (first) first = false else reportCoverage(false)
        }
    }

    val spring = remember { VectraSprings.sheet(0.5f) }
    fun stopSettle() {
        settleJob[0]?.cancel()
        settleJob[0] = null
        settleVelocity[0] = 0f
    }
    fun snapTo(target: Float, velocity: Float = settleVelocity[0]) {
        settleJob[0]?.cancel()
        settleJob[0] = scope.launch {
            try {
                animate(y.floatValue, target, velocity, spring) { value, v ->
                    y.floatValue = value
                    settleVelocity[0] = v
                }
                settleVelocity[0] = 0f
                reportCoverage(true)
            } catch (_: CancellationException) {
                // Interrupted by a new drag or snap.
            }
        }
    }
    fun toggleSnap() = snapTo(if (y.floatValue > latestPeek / 2f) 0f else latestPeek, 0f)

    val grabberBounds = remember { arrayOf(Rect.Zero) }
    val feedBounds = remember { arrayOf(Rect.Zero) }
    val tabsBounds = remember { arrayOf(Rect.Zero) }

    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val safeBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(expandedHeight.dp)
            // A layer translation: the sheet, its shadow and feed move as one cached picture, with
            // no relayout or redraw per frame.
            .graphicsLayer { translationY = y.floatValue * density.density }
            .boxShadow(sheetShape, listOf(BoxShadow(y = -24f, blur = 60f, color = Color.Black.copy(alpha = if (c.isDark) 0.5f else 0.22f))))
            .background(c.surfaceSecondary, sheetShape)
            // A skirt of sheet surface below the bottom edge: the elastic drag and a fast fling's
            // spring can carry the sheet above its expanded snap, which would otherwise lift its
            // bottom off the screen edge and show the map through the gap.
            .drawBehind {
                drawRect(c.surfaceSecondary, topLeft = Offset(0f, size.height - 1f), size = Size(size.width, size.height))
            }
            .topBorder(if (c.isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.1f), 28f)
            .pointerInput(Unit) {
                val px = density.density
                awaitPointerEventScope {
                    while (true) {
                        // ---- down ----
                        var down: PointerInputChange
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            down = ev.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                            break
                        } while (true)
                        suppressNextClick.beginPress()
                        val pointerId = down.id
                        val start = down.position
                        val onGrabber = grabberBounds[0].contains(start)
                        val inFeed = feedBounds[0].contains(start)
                        val onTabs = tabsBounds[0].contains(start)
                        val mode0 = when {
                            onGrabber || y.floatValue > 1f -> GestureMode.Drag
                            inFeed && feedScroll.value > 0 -> null // native scroll owns it
                            else -> GestureMode.Pending
                        }
                        if (mode0 == null) {
                            // Wait for this pointer to lift.
                            do {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = ev.changes.firstOrNull { it.id == pointerId }
                            } while (ch != null && ch.pressed)
                            continue
                        }
                        var mode: GestureMode = mode0
                        val dragStartY = y.floatValue
                        if (mode == GestureMode.Drag) stopSettle()
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, Offset.Zero)
                        // The finger's travel, summed from per-event deltas. The sheet moves under
                        // the finger, so positions in its own coordinates would shrink the drag.
                        var travel = Offset.Zero
                        var locked: Boolean? = null // true = y axis, false = x axis
                        var ended = false
                        while (!ended) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!ch.pressed) {
                                ended = true
                                break
                            }
                            travel += ch.positionChangeIgnoreConsumed()
                            tracker.addPosition(ch.uptimeMillis, travel)
                            val dx = travel.x / px
                            val dy = travel.y / px
                            when (mode) {
                                GestureMode.Drag -> {
                                    if (locked == null && (abs(dx) > 10f || abs(dy) > 10f)) locked = abs(dy) >= abs(dx)
                                    if (locked == true) {
                                        val raw = dragStartY + dy
                                        y.floatValue = elastic(raw, 0f, latestPeek, 0.04f)
                                        ch.consume()
                                    } else if (locked == null && !onTabs && max(abs(dx), abs(dy)) > 3f) {
                                        // Past the pan threshold: the sheet owns this press (no list scroll,
                                        // no tap). The mode tabs keep their own sideways drag.
                                        ch.consume()
                                    }
                                }
                                GestureMode.Pending -> {
                                    if (dy < -6f) {
                                        // A normal upward list scroll, not a sheet drag: let it go.
                                        do {
                                            val e2 = awaitPointerEvent(PointerEventPass.Initial)
                                            val c2 = e2.changes.firstOrNull { it.id == pointerId }
                                        } while (c2 != null && c2.pressed)
                                        ended = true
                                        break
                                    }
                                    if (shouldPullSheet(
                                            sheetY = y.floatValue.toDouble(),
                                            deltaY = dy.toDouble(),
                                            feedScrollTop = if (inFeed) feedScroll.value / px.toDouble() else null,
                                            threshold = 6.0,
                                        )
                                    ) {
                                        mode = GestureMode.Pulling
                                        suppressNextClick.arm()
                                        stopSettle()
                                        y.floatValue = min(latestPeek, max(0f, dy))
                                        ch.consume()
                                    }
                                }
                                GestureMode.Pulling -> {
                                    y.floatValue = min(latestPeek, max(0f, dy))
                                    ch.consume()
                                }
                            }
                        }
                        // ---- up / cancel ----
                        when (mode) {
                            GestureMode.Drag -> {
                                if (locked == true) {
                                    val v = tracker.calculateVelocity().y / px
                                    val cur = y.floatValue
                                    var target = if (cur < latestPeek / 2f) 0f else latestPeek
                                    if (v < -350f) target = 0f
                                    if (v > 350f) target = latestPeek
                                    snapTo(target, v)
                                } else if (onGrabber && abs(y.floatValue - dragStartY) <= 3f) {
                                    toggleSnap()
                                }
                            }
                            // The snap starts from the finger's tracked velocity, so it
                            // carries the finger's speed.
                            GestureMode.Pulling -> snapTo(if (y.floatValue < latestPeek / 2f) 0f else latestPeek, tracker.calculateVelocity().y / px)
                            GestureMode.Pending -> Unit
                        }
                    }
                }
            },
    ) {
        // Drag surface: the grabber.
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 128.dp, height = 32.dp)
                .onGloballyPositioned { grabberBounds[0] = it.boundsInParent() }
                .semantics { contentDescription = "Toggle departures sheet" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 44.dp, height = 6.dp).background(c.textSecondary.copy(alpha = 0.45f), CircleShape))
        }

        ModeTabs(
            modeFilter,
            onToggleMode,
            Modifier
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp)
                .fillMaxWidth()
                .onGloballyPositioned { tabsBounds[0] = it.boundsInParent() },
        )

        val scrolled by remember(feedScroll, density) { derivedStateOf { feedScroll.value > 2 * density.density } }
        Box(
            Modifier
                .padding(top = 8.dp)
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { coords -> feedBounds[0] = coords.boundsInParent() }
                .then(if (scrolled) Modifier.topFadeMask(28f) else Modifier),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(feedScroll)
                    .padding(horizontal = 12.dp)
                    .padding(bottom = safeBottom + 12.dp),
            ) {
                FeedContent(
                    feed, selection, arrivalsEnabled, isLoading, visibleStopCount, polledStopCount,
                    onSelect = { item ->
                        if (suppressNextClick.consume()) return@FeedContent
                        onSelectItem(item)
                        // Selecting reveals the line and vehicles behind the sheet.
                        snapTo(latestPeek, 0f)
                    },
                )
            }
        }
    }
}

/** A top border on a rounded-top surface. */
private fun Modifier.topBorder(color: Color, radiusDp: Float): Modifier = drawWithContent {
    drawContent()
    val r = radiusDp * density
    val w = 1f * density
    val path = Path().apply {
        moveTo(0f, r)
        arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
        lineTo(size.width - r, 0f)
        arcTo(Rect(size.width - 2 * r, 0f, size.width, 2 * r), 270f, 90f, false)
    }
    drawPath(path, color, style = Stroke(w))
}

/** Fades the content in over the top [fadeDp] (transparent at the edge, opaque below). */
private fun Modifier.topFadeMask(fadeDp: Float): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = fadeDp * density
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = h),
            size = Size(size.width, h),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * The mode switcher as Liquid Glass tabs, like the dock: a drop of glass you tap or drag between
 * All, Metro, Tram, Bus and Trolley. The glass refracts a recording of the sheet beneath it.
 */
@Composable
private fun ModeTabs(modeFilter: List<String>, onToggleMode: (String) -> Unit, modifier: Modifier) {
    val selected = if (modeFilter.isEmpty()) 0 else ModeChips.indexOfFirst { it.id in modeFilter }.coerceAtLeast(0)
    // iOS: the system segmented control (Liquid Glass on iOS 26) instead of the Compose glass tabs.
    if (hasNativeSegmentedControl) {
        NativeSegmentedControl(
            labels = ModeChips.map { it.label },
            selectedIndex = selected,
            onSelect = { index -> onToggleMode(ModeChips[index].id) },
            modifier = modifier.height(36.dp),
        )
        return
    }
    val c = Vectra.colors
    val haptics = Vectra.haptics
    val frosted = !isRenderEffectSupported()
    val backdrop = rememberLayerBackdrop()
    Box(modifier) {
        // What the glass sees: the sheet's own surface, recorded beside (not inside) the tabs.
        Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(c.surfaceSecondary))
        LiquidTabs(
            count = ModeChips.size,
            selectedIndex = selected,
            onSelect = { index -> onToggleMode(ModeChips[index].id) },
            onCross = { haptics.frequentTick() },
            backdrop = backdrop,
            accent = c.accentPrimary,
            container = if (frosted) c.glassBg.copy(alpha = (c.glassBg.alpha * 1.8f).coerceAtMost(1f)) else c.surfacePrimary.copy(alpha = 0.54f),
            modifier = Modifier.fillMaxWidth(),
            height = 48.dp,
            frosted = frosted,
        ) { index, tinted ->
            VText(
                ModeChips[index].label,
                Modifier.semantics { contentDescription = ModeChips[index].label },
                style = vtext(12.sp to 16.sp, Weight.Semibold),
                color = if (tinted) c.accentPrimary else c.textSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FeedContent(
    feed: List<RadarDepartureItem>,
    selection: NearbySelectionKey?,
    arrivalsEnabled: Boolean,
    isLoading: Boolean,
    visibleStopCount: Int,
    polledStopCount: Int,
    onSelect: (RadarDepartureItem) -> Unit,
) {
    val c = Vectra.colors
    when {
        !arrivalsEnabled -> FeedNote("Zoom in to see departures")
        feed.isEmpty() -> FeedNote(if (isLoading) "Scanning nearby stops…" else "No upcoming departures near this area — pan or zoom in.")
        else -> {
            // One card per route, stop and direction; its next departures share the ETA column.
            val groupedFeed = remember(feed) {
                val groups = LinkedHashMap<String, MutableList<RadarDepartureItem>>()
                for (item in feed) groups.getOrPut("${item.routeId}|${item.stopId}|${item.headsign}") { mutableListOf() } += item
                groups.map { (key, items) -> key to items.toList() }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((key, items) in groupedFeed) {
                    key(key) {
                        val isSelected = selection != null && items.any {
                            selection.stopId == it.stopId && selection.routeId == it.routeId && selection.headsign == it.headsign
                        }
                        DepartureCard(items, isSelected) { onSelect(items[0]) }
                    }
                }
                if (visibleStopCount > polledStopCount) {
                    VText(
                        "Showing departures from the $polledStopCount closest stops of $visibleStopCount in view",
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 8.dp),
                        vtext(TypeScale.Micro, Weight.Medium),
                        c.textMuted,
                        align = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedNote(text: String) {
    VText(
        text,
        Modifier.fillMaxWidth().padding(24.dp),
        vtext(TypeScale.Caption),
        Vectra.colors.textMuted,
        align = TextAlign.Center,
    )
}

/** The departure card shared by the sheet feed and the explorer's selection header. */
@Composable
private fun DepartureCard(items: List<RadarDepartureItem>, isSelected: Boolean, onClick: () -> Unit) {
    val c = Vectra.colors
    val item = items[0]
    val shape = Radius.Panel
    val press = remember { MutableInteractionSource() }
    val border = when {
        isSelected -> nearbyHexAlpha(item.color, 0x80)
        c.isDark -> Color.White.copy(alpha = 0.06f)
        else -> Color.Black.copy(alpha = 0.08f)
    }
    val bg = if (isSelected) nearbyHexAlpha(item.color, 0x18) else c.surfaceSecondary
    val shadow = if (isSelected) listOf(BoxShadow(y = 5f, blur = 18f, color = nearbyHexAlpha(item.color, 0x18))) else NearbyShadows.sm()
    Row(
        Modifier
            .pressScale(press, 0.985f)
            .fillMaxWidth()
            .height(96.dp)
            .boxShadow(shape, shadow)
            .background(bg, shape)
            .border(1.dp, border, shape)
            .tappable(role = null, highlight = false, interactionSource = press, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NearbyDepartureCardBody(
            color = item.color,
            textColor = item.textColor,
            shortName = item.shortName,
            headsign = item.headsign,
            stopName = item.stopName,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (index in 0 until 3) {
                    val departure = items.getOrNull(index)
                    if (departure != null) {
                        NearbyEtaRow(departure.isPredicted, formatNearbyCountdown(departure.countdownMinutes))
                    } else {
                        Box(Modifier.alpha(0f)) { NearbyEtaRow(false, "0'") }
                    }
                }
            }
        }
    }
}

/** Card layout: the large route badge, the direction and stop, then [eta] on the right. */
@Composable
internal fun RowScope.NearbyDepartureCardBody(
    color: String,
    textColor: String,
    shortName: String,
    headsign: String,
    stopName: String,
    eta: @Composable () -> Unit,
) {
    val c = Vectra.colors
    Spacer(Modifier.width(12.dp))
    // At least the badge's square, but wide names (28/28A, 240E) widen it instead of clipping.
    Box(Modifier.widthIn(min = 48.dp).height(48.dp), contentAlignment = Alignment.Center) {
        RouteBadge(shortName, color, textColor, size = RouteBadgeSize.Lg)
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f).padding(vertical = 2.dp), verticalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                VIcon(RemixIcons.ArrowRightLine, size = 12.dp, tint = c.textSecondary)
            }
            VText(headsign.ifEmpty { "Any direction" }, style = vtext(11.sp to 16.5.sp, Weight.Bold), color = c.textPrimary, maxLines = 1)
        }
        Row(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(6.dp).background(c.textSecondary.copy(alpha = 0.6f), CircleShape))
            }
            VText(stopName, style = vtext(TypeScale.Micro, Weight.Medium), color = c.textMuted, maxLines = 1)
        }
    }
    Spacer(Modifier.width(12.dp))
    eta()
    Spacer(Modifier.width(12.dp))
}

/** The live/scheduled icon and the countdown as one unit. */
@Composable
internal fun NearbyEtaRow(isPredicted: Boolean, text: String) {
    val c = Vectra.colors
    val tone = if (isPredicted) c.tints.emerald.text else c.textPrimary.copy(alpha = 0.8f)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (isPredicted) LiveSignalIcon(size = 14.dp, tint = tone) else VIcon(RemixIcons.TimeLine, size = 14.dp, tint = tone)
        }
        VText(text, style = vtext(TypeScale.Body.first to TypeScale.Body.first, Weight.Black, Tracking.Tight, mono = true), color = tone, maxLines = 1, softWrap = false)
    }
}
