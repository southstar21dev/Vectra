package dev.southstar21.vectra.core.maps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The round vehicle token: a disc in the line's colour with a white ring, a vehicle drawn on it,
 * and a white point on the ring for the way it is heading. The line strip, the route map (both
 * as a Compose marker and as a map style image) and the stop list all draw this one shape.
 */

/** Ring width as a share of the token's radius. */
private const val RingShare = 0.19f

/** The heading point: how far it reaches past the ring, and half its base, as shares of the radius. */
private const val NubReach = 0.5f
private const val NubHalfBase = 0.46f

/**
 * A token of [radius] px centred on [center]. [headingDeg] is the way it points, as on a compass
 * (0 up, 90 right); null draws no point. [gap] cuts it out of whatever it sits on (the line, a
 * card) by [gapWidth] px.
 */
fun DrawScope.drawVehicleToken(
    center: Offset,
    radius: Float,
    fill: Color,
    ink: Color,
    headingDeg: Float?,
    ring: Color = Color.White,
    gap: Color? = null,
    gapWidth: Float = 0f,
    alpha: Float = 1f,
) {
    if (gap != null && gapWidth > 0f) drawCircle(gap, radius + gapWidth, center)
    if (headingDeg != null) drawHeadingPoint(center, radius, headingDeg, ring.copy(alpha = ring.alpha * alpha), gap, gapWidth)
    drawCircle(ring.copy(alpha = ring.alpha * alpha), radius, center)
    drawCircle(fill.copy(alpha = fill.alpha * alpha), radius * (1f - RingShare), center)
    drawVehicleGlyph(center, radius * 1.02f, ink.copy(alpha = ink.alpha * alpha), fill.copy(alpha = fill.alpha * alpha))
}

/** The white point on the ring, turned to [headingDeg]; outlined in [gap] so it reads on the line. */
private fun DrawScope.drawHeadingPoint(center: Offset, radius: Float, headingDeg: Float, color: Color, gap: Color?, gapWidth: Float) {
    rotate(headingDeg, pivot = center) {
        val tipY = center.y - radius * (1f + NubReach)
        val baseY = center.y - radius * 0.62f
        val half = radius * NubHalfBase
        val point = Path().apply {
            moveTo(center.x, tipY)
            lineTo(center.x + half, baseY)
            lineTo(center.x - half, baseY)
            close()
        }
        if (gap != null && gapWidth > 0f) {
            drawPath(point, gap, style = Stroke(gapWidth * 2f))
        }
        drawPath(point, color)
    }
}

/** A vehicle seen from the front (body, windscreen, two wheels), [size] px tall, in [ink]. */
fun DrawScope.drawVehicleGlyph(center: Offset, size: Float, ink: Color, window: Color) {
    val u = size / 2f
    fun at(x: Float, y: Float) = Offset(center.x + x * u, center.y + y * u)
    val bodyTop = at(-0.5f, -0.56f)
    drawRoundRect(ink, bodyTop, Size(u * 1.0f, u * 0.92f), CornerRadius(u * 0.22f))
    drawRoundRect(window, at(-0.34f, -0.4f), Size(u * 0.68f, u * 0.32f), CornerRadius(u * 0.08f))
    drawCircle(ink, u * 0.13f, at(-0.27f, 0.5f))
    drawCircle(ink, u * 0.13f, at(0.27f, 0.5f))
}

/** The token as a Compose element (place it with `Modifier.placedAt` on a map). */
@Composable
fun VehicleToken(
    fill: Color,
    ink: Color,
    headingDeg: () -> Float?,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    alpha: Float = 1f,
) {
    // Room for the heading point around the disc.
    Canvas(modifier.size(size * 1.6f)) {
        val r = size.toPx() / 2f
        drawVehicleToken(Offset(this.size.width / 2f, this.size.height / 2f), r, fill, ink, headingDeg(), alpha = alpha)
    }
}

/** The token without its point, as a map style image (it never turns with the map). */
internal class VehicleTokenBodyPainter(private val fill: Color, private val ink: Color, private val alpha: Float) : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified
    override fun DrawScope.onDraw() {
        drawVehicleToken(center, size.minDimension / 2f / 1.6f, fill, ink, headingDeg = null, alpha = alpha)
    }
}

/** Only the heading point, pointing north, in the same box as [VehicleTokenBodyPainter]; the layer turns it. */
internal class VehicleTokenPointPainter(private val alpha: Float) : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified
    override fun DrawScope.onDraw() {
        val r = size.minDimension / 2f / 1.6f
        drawHeadingPoint(center, r, 0f, Color.White.copy(alpha = alpha), gap = null, gapWidth = 0f)
    }
}
