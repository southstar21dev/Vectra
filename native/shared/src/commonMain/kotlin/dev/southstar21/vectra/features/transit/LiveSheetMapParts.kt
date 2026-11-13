package dev.southstar21.vectra.features.transit

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.VectraSprings
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.specularBorder
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/* The live tracker sheet's map: growing it in place, and the hint that it can be grown. */

/** The map's share of the sheet; the stops' heading peeks out under it at half height. */
private const val MapShare = 0.56f
private val StopsPeek = 60.dp

/**
 * The map's heights in the sheet: [full] (the whole sheet, what the map is always laid out at),
 * [rest] (shown while small) and [shown], the animated height of the box showing it, read in
 * layout so the growth never recomposes the map.
 */
@Stable
internal class MapSizes(val full: Dp, val rest: Dp, val shown: () -> Dp)

/**
 * The map over the scrolling stops. Grown ([mapExpanded]), the map takes the whole (pinned open)
 * sheet, the list scrolls back to the top and holds still under it.
 */
@Composable
internal fun ColumnScope.GrowingMapLayout(mapExpanded: Boolean, content: @Composable ColumnScope.(MapSizes) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        val full = maxHeight
        val rest = (maxHeight * MapShare - StopsPeek).coerceAtLeast(240.dp)
        val shown = animateDpAsState(
            if (mapExpanded) full else rest,
            if (Vectra.reduceMotion) snap() else VectraSprings.sheet(Dp.VisibilityThreshold),
            label = "mapHeight",
        )
        val sizes = remember(full, rest) { MapSizes(full, rest) { shown.value } }
        val scroll = rememberScrollState()
        LaunchedEffect(mapExpanded) { if (mapExpanded) scroll.animateScrollTo(0) }
        Column(Modifier.fillMaxSize().verticalScroll(scroll, enabled = !mapExpanded)) { content(sizes) }
    }
}

/** The map's grown state and the "tap to explore" hint, handed from the sheet host down to the map. */
@Immutable
internal class MapSheetState(val expanded: Boolean, val onExpandedChange: (Boolean) -> Unit, val hint: MapHint)

/** How many sheet openings show the hint, unless the map has been grown before. */
private const val MapHintTimes = 3
private const val MapHintKey = "transit.liveMapHintShown"
private const val MapHintMs = 4_000L

/**
 * The "tap to explore the map" hint: armed for each of the first [MapHintTimes] openings of a
 * tracker's sheet, and never again once the map has been grown.
 */
@Stable
internal class MapHint(private val kv: KeyValueStore, private val scope: CoroutineScope) {
    var armed by mutableStateOf(false)
        private set

    suspend fun arm() {
        armed = (runSuspendCatching { kv.get(MapHintKey) }.getOrNull()?.toIntOrNull() ?: 0) < MapHintTimes
    }

    /** Shown once more. */
    fun shown() {
        armed = false
        scope.launch {
            runSuspendCatching {
                val n = kv.get(MapHintKey)?.toIntOrNull() ?: 0
                kv.set(MapHintKey, (n + 1).toString())
            }
        }
    }

    /** The map has been grown: no more hints. */
    fun learned() {
        armed = false
        scope.launch { runSuspendCatching { kv.set(MapHintKey, MapHintTimes.toString()) } }
    }
}

@Composable
internal fun rememberMapHint(kv: KeyValueStore, trackerId: String?): MapHint {
    val scope = rememberCoroutineScope()
    val hint = remember(kv) { MapHint(kv, scope) }
    LaunchedEffect(trackerId) { if (trackerId != null) hint.arm() }
    return hint
}

/** A glass label over the small map telling that it opens up; fades in and away on its own. */
@Composable
private fun MapHintPill(visible: Boolean, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    val spec = if (Vectra.reduceMotion) tween<Float>(150) else tween(280, easing = VectraEasing.Decelerate)
    val shown by animateFloatAsState(if (visible) 1f else 0f, spec, label = "mapHint")
    if (shown <= 0f && !visible) return
    val shape = CircleShape
    // No touch handling of its own: a tap on it reaches the map under it, which grows.
    Row(
        modifier
            .graphicsLayer {
                alpha = shown
                translationY = (1 - shown) * 8.dp.toPx()
            }
            .boxShadow(shape, c.glassShadow.filter { !it.inset })
            .background(c.glassBgElevated.copy(alpha = 0.9f), shape)
            .specularBorder(shape, c.glassBorder, c.glassBorderSpecular)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(RemixIcons.FullscreenLine, size = 14.dp, tint = c.textPrimary)
        VText("Tap to explore the map", style = vtext(TypeScale.Caption, Weight.Semibold), color = c.textPrimary, maxLines = 1)
    }
}

/**
 * Reaching for the map asks for it: a tap, a second finger (a pinch) or a pan that runs more
 * sideways than up or down calls [onExpand]. Up-and-down swipes are left to the sheet; nothing is
 * consumed, so they still move it.
 */
@Composable
internal fun Modifier.mapExpandGestures(onExpand: () -> Unit): Modifier {
    val expand by rememberUpdatedState(onExpand)
    return semantics {
        role = Role.Button
        onClick(label = "Expand the map") { expand(); true }
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            var moved = Offset.Zero
            var wantsMap = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                when {
                    event.changes.count { it.pressed } >= 2 -> wantsMap = true
                    change == null -> Unit
                    !change.pressed -> wantsMap = moved.getDistance() < slop
                    else -> moved += change.positionChange()
                }
                val decided = wantsMap || change == null || !change.pressed || moved.getDistance() > slop
                if (decided) {
                    if (!wantsMap && moved.getDistance() > slop) wantsMap = abs(moved.x) > abs(moved.y)
                    break
                }
            }
            if (wantsMap) expand()
        }
    }
}

/** Height from [height], read in layout: animating it re-measures this box without recomposing it. */
internal fun Modifier.layoutHeight(height: () -> Dp): Modifier = layout { measurable, constraints ->
    val h = height().roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
    layout(placeable.width, h) { placeable.place(0, 0) }
}

/** The hint over the small map, once it is [ready]: fades in, stays a moment, and counts as shown. */
@Composable
internal fun MapHintSlot(mapSheet: MapSheetState, ready: Boolean, modifier: Modifier = Modifier) {
    val hintNow = mapSheet.hint.armed && !mapSheet.expanded && ready
    var hintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(hintNow) {
        if (!hintNow) return@LaunchedEffect
        try {
            delay(500)
            hintVisible = true
            delay(MapHintMs)
        } finally {
            hintVisible = false
        }
        mapSheet.hint.shown()
    }
    MapHintPill(hintVisible && !mapSheet.expanded, modifier)
}
