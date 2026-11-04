package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.LocalContentColor
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.mixTransparent
import kotlinx.coroutines.delay
import dev.southstar21.vectra.core.designsystem.theme.Radius

/** A comet-tail ring in the accent, spinning once a second. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 32.dp, color: Color = Vectra.colors.accentPrimary, thickness: Dp = 3.dp) {
    // Reduced motion slows the spinner rather than freezing the only sign of loading.
    val spin = rememberLoop(if (Vectra.reduceMotion) 2400 else 1000)
    Canvas(modifier.size(size).graphicsLayer { rotationZ = spin.value * 360f }) {
        val stroke = thickness.toPx()
        // Conic gradient from 12 o'clock: transparent → 15% → full at 92%, then a hard cut.
        val brush = Brush.sweepGradient(
            0f to Color.Transparent,
            0.10f to Color.Transparent,
            0.30f to color.mixTransparent(15f),
            0.92f to color,
            0.9201f to Color.Transparent,
            1f to Color.Transparent,
        )
        rotate(-90f) {
            drawCircle(brush, radius = (this.size.minDimension - stroke) / 2f, style = Stroke(stroke))
        }
    }
}

/**
 * A ring with its top quarter cut out, spinning linearly once a second (empty states,
 * the loading screen).
 */
@Composable
fun RingSpinner(modifier: Modifier = Modifier, size: Dp = 12.dp, color: Color = Vectra.colors.borderSubtle, thickness: Dp = 1.dp) {
    val spin = rememberLoop(1000)
    Canvas(modifier.size(size).graphicsLayer { rotationZ = spin.value * 360f }) {
        val stroke = thickness.toPx()
        val inset = stroke / 2f
        // Compose angles run clockwise from 3 o'clock; the transparent top border spans 225°–315°.
        drawArc(
            color,
            startAngle = -45f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(stroke),
        )
    }
}

/** Small inline loader: the Loader4 icon spinning once a second. */
@Composable
fun InlineLoader(modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color = LocalContentColor.current) {
    val spin = rememberLoop(1000)
    VIcon(RemixIcons.Loader4Line, modifier.graphicsLayer { rotationZ = spin.value * 360f }, size, tint)
}

/** Skeleton pulse: opacity 1 → .5 → 1 over 2s. */
@Composable
fun Modifier.pulse(): Modifier {
    if (Vectra.reduceMotion) return this
    val t = rememberLoop(1000, CubicBezierEasing(0.4f, 0f, 0.6f, 1f), reverse = true)
    return graphicsLayer { alpha = 1f - 0.5f * t.value }
}

@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = Radius.Control) {
    Box(modifier.pulse().background(Vectra.colors.surfaceOverlay, shape))
}

private val ZzzLetters = listOf("M3 15h6l-6 6h6", "M11.5 9.5h4.5l-4.5 4.5h4.5", "M17.5 4h3.5l-3.5 3.5h3.5")

/** Zzz letters drifting up in sequence (2.4s, staggered 0.3s). */
@Composable
fun SleepingIcon(modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = LocalContentColor.current) {
    val paths = remember { ZzzLetters.map { PathParser().parsePathString(it).toPath() } }
    val reduce = Vectra.reduceMotion
    val onScreen = rememberOnScreen()
    val loop = rememberLoop(2400, enabled = !reduce && onScreen.value, maxFps = DecorativeLoopFps)
    // A delayed letter holds on its first keyframe (hidden)
    // until its delay has run once; afterwards the stagger just phase-shifts the loop.
    var primed by remember { mutableStateOf(false) }
    LaunchedEffect(reduce) { if (!reduce) { delay(600); primed = true } }
    // Its own layer: without one, every tick re-records whatever surrounds the icon.
    Canvas(modifier.size(size).trackOnScreen(onScreen).graphicsLayer { }) {
        val s = this.size.minDimension / 24f
        paths.forEachIndexed { i, path ->
            val clock = loop.value * 2400f
            val p = ((clock - i * 300f) % 2400f + 2400f) % 2400f / 2400f
            // Drift keyframes with ease-in-out per segment
            val (alpha, dx, dy) = when {
                reduce -> Triple(1f, 0f, 0f)
                !primed && clock < i * 300f -> Triple(0f, -1f, 1.5f)
                p < 0.2f -> { val k = ease(p / 0.2f); Triple(k, -1f + k, 1.5f - 1.5f * k) }
                p < 0.7f -> Triple(1f, 0f, 0f)
                else -> { val k = ease((p - 0.7f) / 0.3f); Triple(1f - k, k, -1.5f * k) }
            }
            drawContext.transform.translate(dx * s, dy * s)
            drawContext.transform.scale(s, s, Offset.Zero)
            drawPath(path, tint.copy(alpha = tint.alpha * alpha), style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawContext.transform.scale(1f / s, 1f / s, Offset.Zero)
            drawContext.transform.translate(-dx * s, -dy * s)
        }
    }
}

private fun ease(x: Float): Float = CubicBezierEasing(0.42f, 0f, 0.58f, 1f).transform(x.coerceIn(0f, 1f))
