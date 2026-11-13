package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A full-screen view opened from a card at [origin] (window px), which it grows out of; null fades it in. */
class CardLaunch(val origin: Rect?)

/** How far a [CardGrowOverlay] has got: [grow] 0 (the card) to 1 (the window); [opened] once it has all but filled it. */
class CardGrowState(val grow: () -> Float, val opened: Boolean, val origin: Rect?)

/**
 * A full-screen view that grows out of the card it was opened from ([launch]) and shrinks back into
 * it when [launch] goes null; back closes it through [onClose]. The card under it shows through at
 * first, so the view seems to come out of it. [content] gets the grow's progress and the card's
 * place in this box.
 */
@Composable
fun CardGrowOverlay(
    launch: CardLaunch?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = 24.dp,
    content: @Composable (CardGrowState) -> Unit,
) {
    // The last launch stays while the view shrinks away.
    var shown by remember { mutableStateOf(launch) }
    if (launch != null && launch !== shown) shown = launch
    val s = shown ?: return
    val reduced = Vectra.reduceMotion
    val grow = remember(s) { Animatable(0f) }
    var opened by remember(s) { mutableStateOf(false) }
    val plain = reduced || s.origin == null
    LaunchedEffect(s, launch == null) {
        if (launch != null) {
            // Opened once it has all but filled the window: the spring's last pixels don't hold the rest up.
            launch {
                snapshotFlow { grow.value }.first { it > 0.97f }
                opened = true
            }
            // The view's first frames are the costly ones (it's being built): start the grow after
            // them, so it plays from the card instead of jumping to a third of the way.
            if (grow.value == 0f) {
                withFrameNanos { }
                withFrameNanos { }
            }
            grow.animateTo(1f, if (plain) tween(200) else spring(dampingRatio = 0.9f, stiffness = 240f))
        } else {
            grow.animateTo(0f, if (plain) tween(180) else tween(340, easing = FastOutSlowInEasing))
            shown = null
        }
    }
    VBackHandler(enabled = launch != null, onBack = onClose)
    var hostAt by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val origin = s.origin?.translate(-hostAt)
    val shape = remember(origin) { origin?.let { GrowShape(it, with(density) { corner.toPx() }) { grow.value } } }
    Box(modifier.fillMaxSize().onPlaced { hostAt = it.positionInWindow() }.pointerInput(Unit) {}) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = grow.value.coerceIn(0f, 1f) }.background(Color.Black.copy(alpha = 0.35f)))
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val g = grow.value.coerceIn(0f, 1f)
                alpha = if (shape == null) g else (g / 0.3f).coerceAtMost(1f)
                if (shape != null) {
                    this.shape = shape
                    clip = true
                }
            },
        ) {
            content(CardGrowState({ grow.value.coerceIn(0f, 1f) }, opened, origin))
        }
    }
}

/** The rounded rectangle between the card ([from], with [corner] px corners) and the full window, [progress] of the way. */
private class GrowShape(private val from: Rect, private val corner: Float, private val progress: () -> Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val g = progress().coerceIn(0f, 1f)
        val r = lerp(from, Rect(Offset.Zero, size), g)
        val radius = corner * (1 - g)
        return Outline.Rounded(RoundRect(r, CornerRadius(radius)))
    }
}
