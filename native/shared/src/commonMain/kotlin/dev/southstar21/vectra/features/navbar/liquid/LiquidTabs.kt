// Tab bar construction adapted from Kyant0/AndroidLiquidGlass's LiquidBottomTabs (Apache-2.0), via the Kaloria app.
package dev.southstar21.vectra.features.navbar.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import dev.southstar21.vectra.core.designsystem.theme.tappable

/**
 * iOS 26-style Liquid Glass tabs: one pane of glass whose selection is itself a drop of glass you
 * can tap, or press and drag between items. The drop magnifies the item beneath it (shown in the
 * accent colour) with a little chromatic aberration, swells while held and squashes with speed.
 *
 * [onSelect] fires once the drop has nearly arrived, so the page switch doesn't make the drop
 * stutter on its way there.
 *
 * @param frosted no live refraction available: the pane is a plain frosted fill.
 * @param item one item's content; [tinted] is the accent copy the drop shows.
 */
@Composable
fun LiquidTabs(
    count: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    accent: Color,
    container: Color,
    modifier: Modifier = Modifier,
    /** The resting fill of the selection drop. */
    selection: Color = accent.copy(alpha = 0.25f),
    height: Dp = 64.dp,
    frosted: Boolean = false,
    /** The drop crossed onto another item: a detent, for a haptic tick. */
    onCross: () -> Unit = {},
    item: @Composable ColumnScope.(index: Int, tinted: Boolean) -> Unit,
) {
    val selectedNow by rememberUpdatedState(selectedIndex)
    val onSelectNow by rememberUpdatedState(onSelect)
    val shadowColor = Color.Black.copy(alpha = 0.10f)
    val itemsBackdrop = rememberLayerBackdrop()
    val pillHeight = height - 8.dp

    // The width is state read only by layout, draw and gesture lambdas: the bar narrowing as the +
    // grows in must not recompose the tabs every frame (it made the + stutter in).
    var widthPx by remember { mutableFloatStateOf(0f) }
    Box(modifier.onSizeChanged { widthPx = it.width.toFloat() }, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val inset = with(density) { 8.dp.toPx() }
        val itemWidthState = remember(count, inset) { derivedStateOf { ((widthPx - inset) / count).coerceAtLeast(1f) } }
        val itemWidth by itemWidthState
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / widthPx.coerceAtLeast(1f)).fastCoerceIn(-1f, 1f)
                with(density) { 4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }
        val scope = rememberCoroutineScope()
        // One state for the tabs' lifetime: taps and drags write it, the caller follows it.
        var currentIndex by remember { mutableIntStateOf(selectedIndex) }
        val drag = remember(scope, count) {
            DampedDragAnimation(
                animationScope = scope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(count - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, count - 1)
                    currentIndex = target
                    animateToValue(target.toFloat())
                    scope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
                },
                onDrag = { _, amount ->
                    updateValue((targetValue + amount.x / itemWidth).fastCoerceIn(0f, (count - 1).toFloat()))
                    scope.launch { offsetAnimation.snapTo(offsetAnimation.value + amount.x) }
                },
            )
        }
        // Follow selection changes that didn't come from the tabs (back, swipes).
        LaunchedEffect(selectedIndex) {
            if (currentIndex != selectedIndex) {
                currentIndex = selectedIndex
                drag.animateToValue(selectedIndex.toFloat())
            }
        }
        val onCrossNow by rememberUpdatedState(onCross)
        // A detent each time the drop's centre passes onto another item, dragged or animating.
        LaunchedEffect(drag) {
            snapshotFlow { drag.value.fastRoundToInt() }.drop(1).collect { onCrossNow() }
        }
        LaunchedEffect(drag) {
            snapshotFlow { currentIndex }.drop(1).collectLatest { index ->
                drag.animateToValue(index.toFloat())
                snapshotFlow { abs(drag.value - index) < 0.2f }.first { it }
                if (index != selectedNow) onSelectNow(index)
            }
        }

        val highlight = remember(scope) {
            InteractiveHighlight(scope, position = { size, _ -> Offset((drag.value + 0.5f) * itemWidth + panelOffset, size.height / 2f) })
        }

        @Composable
        fun items(tinted: Boolean, rowModifier: Modifier, scale: () -> Float = { 1f }) = Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            repeat(count) { index ->
                Column(
                    Modifier
                        .clip(Capsule())
                        .tappable(role = Role.Tab, highlight = false) { currentIndex = index }
                        .semantics { selected = index == selectedIndex }
                        .fillMaxHeight()
                        .weight(1f)
                        .graphicsLayer { val s = scale(); scaleX = s; scaleY = s },
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { item(index, tinted) }
            }
        }

        // The pane: one piece of glass holding the items.
        items(
            tinted = false,
            rowModifier = Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        if (frosted) blur(32.dp.toPx()) else {
                            // iOS's bar material scatters what's behind it well past legibility.
                            vibrancy(); blur(10.dp.toPx()); lens(20.dp.toPx(), 28.dp.toPx())
                        }
                    },
                    // A faint rim: the system bar's edge barely catches the light.
                    highlight = { Highlight.Default.copy(alpha = 0.3f) },
                    layerBlock = {
                        val s = lerp(1f, 1f + 16.dp.toPx() / size.width, drag.pressProgress)
                        scaleX = s; scaleY = s
                    },
                    shadow = { Shadow(radius = 24.dp, color = shadowColor) },
                    onDrawSurface = { drawRect(container) },
                )
                .then(highlight.modifier)
                .height(height)
                .fillMaxWidth()
                .padding(4.dp),
        )

        // An invisible accent-tinted copy of the items, recorded so the drop shows the item under
        // it in the accent colour as it passes. Its own glass only exists while the drop is held:
        // at rest a flat fill looks the same through the drop and spares a backdrop per scroll frame.
        val held by remember(drag) { derivedStateOf { drag.pressProgress > 0f } }
        items(
            tinted = true,
            scale = { lerp(1f, 1.2f, drag.pressProgress) },
            rowModifier = Modifier
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(itemsBackdrop)
                .graphicsLayer { translationX = panelOffset }
                .then(
                    if (held) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = {
                                val p = drag.pressProgress
                                vibrancy()
                                blur(8.dp.toPx() * p)
                                lens(24.dp.toPx() * p, 24.dp.toPx() * p)
                            },
                            highlight = { Highlight.Default.copy(alpha = drag.pressProgress) },
                            onDrawSurface = { drawRect(container) },
                        )
                    } else {
                        // Seen only through the resting pill, which doesn't blur: nearly opaque, so
                        // the page doesn't show sharp through it.
                        Modifier.background(container.copy(alpha = container.alpha.coerceAtLeast(0.94f)), Capsule())
                    },
                )
                .then(highlight.modifier)
                .height(pillHeight)
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .graphicsLayer(colorFilter = ColorFilter.tint(accent)),
        )

        // The selection: a drop of glass, always lensing the accent copy under it, that swells and
        // magnifies more while held.
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer { translationX = drag.value * itemWidth + panelOffset }
                .then(highlight.gestureModifier)
                .then(drag.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, itemsBackdrop),
                    shape = { Capsule() },
                    // At rest the selection is a flat pill, as on iOS; it only turns into a lensing
                    // drop of glass while held.
                    effects = {
                        val p = drag.pressProgress
                        if (p > 0f) lens(10.dp.toPx() * p, 14.dp.toPx() * p, chromaticAberration = true)
                    },
                    highlight = { Highlight.Default.copy(alpha = drag.pressProgress) },
                    shadow = { Shadow(radius = 8.dp, alpha = drag.pressProgress) },
                    innerShadow = { InnerShadow(radius = 3.dp + 5.dp * drag.pressProgress, alpha = drag.pressProgress) },
                    layerBlock = {
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                        val v = drag.velocity / 10f
                        scaleX /= 1f - (v * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (v * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val p = drag.pressProgress
                        drawRect(selection, alpha = 1f - p)
                        drawRect(Color.Black.copy(alpha = 0.054f * p))
                    },
                )
                .height(pillHeight)
                .fillMaxWidth(1f / count),
        )
    }
}
