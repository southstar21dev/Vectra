package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.maps.drawVehicleToken
import kotlin.math.abs


private val DotRadius = 5.dp
private val DotStroke = 2.5.dp
private val YouRadius = 10.dp
private val YouRing = 5.dp
/** The round vehicle token's diameter (its heading point reaches a little further). */
private val MarkerW = 26.dp
private val Halo = 3.dp
private val LineW = 4.dp
private val EdgeInset = 6.dp

/**
 * The line through your stop: three stops each side, vehicles approaching from both ends
 * with their times beside them (above for right-heading, below for left-heading).
 * [labels] false draws the slim version: just the line and the markers.
 */
@Composable
internal fun LineStrip(model: StripModel, colors: StripColors, modifier: Modifier = Modifier, labels: Boolean = true) {
    val density = LocalDensity.current
    val top = with(density) { stripRoom(model, labels, above = true) }
    val bottom = with(density) { stripRoom(model, labels, above = false) }
    val mid = top + YouRadius
    val total = top + YouRadius * 2 + bottom
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = EdgeInset).height(total).clearAndSetSemantics { }) {
        val w = maxWidth
        val n = StripStopCount
        fun xOf(u: Float): Dp = w * (u / (n - 1))
        Canvas(Modifier.fillMaxWidth().height(total)) { drawStrip(model, colors, mid.toPx()) }
        if (labels) {
            for (v in model.vehicles) {
                if (v.label != null || v.sub != null) PlacedLabel(v, xOf(v.position), v.position / (n - 1), mid)
            }
        }
    }
}

/**
 * Room above or below the line: a label's height (with its second line) where vehicles have
 * labels, a little otherwise, and at least the fork's spread when the line forks.
 */
private fun Density.stripRoom(model: StripModel, labels: Boolean, above: Boolean): Dp {
    if (!labels) return 10.dp
    val hasLabels = if (above) model.hasLabelsAbove else model.hasLabelsBelow
    val hasSubs = if (above) model.hasSubsAbove else model.hasSubsBelow
    val room = if (hasLabels) 20.sp.toDp() + (if (hasSubs) 15.sp.toDp() else 0.dp) + 12.dp else 12.dp
    return if (model.fork != null) maxOf(room, 30.dp) else room
}

/**
 * Track, stops, markers, then your stop. A stop a marker partly covers would peek out beside it
 * as a crescent, so a small stop under a marker is left out; your stop is left out while a marker
 * sits on it and drawn over one that only touches its edge.
 */
private fun DrawScope.drawStrip(model: StripModel, colors: StripColors, mid: Float) {
    // Each marker's centre, held within the strip's inset at the ends instead of hanging off it.
    val half = MarkerW.toPx() / 2
    val inset = EdgeInset.toPx()
    val markers = model.vehicles.filter { it.showMarker }.map { it to stripX(it.position).coerceIn(half - inset, size.width - half + inset) }
    val xs = markers.map { it.second }
    drawTrack(model, colors, mid)
    drawStops(model, colors, mid, xs)
    drawMarkers(markers, colors, mid)
    val yc = Offset(stripX(StripSideStops.toFloat()), mid)
    if (xs.none { abs(it - yc.x) < half + Halo.toPx() }) {
        val yr = YouRadius.toPx()
        drawCircle(colors.card, yr + 4.dp.toPx(), yc)
        drawCircle(colors.line, yr, yc)
        drawCircle(colors.you, yr - YouRing.toPx(), yc)
    }
}

private fun DrawScope.stripX(u: Float) = size.width * (u / (StripStopCount - 1))

private fun DrawScope.drawTrack(model: StripModel, colors: StripColors, mid: Float) {
    val n = StripStopCount
    val you = StripSideStops
    val w = size.width
    fun x(u: Float) = stripX(u)
    val lineW = LineW.toPx()
    val lineColor = colors.line.copy(alpha = colors.line.alpha * 0.45f)
    // Track: the part after your stop goes grey when nothing tracked comes from there.
    val forkSegment = model.fork?.let { if (it.heading > 0) n - 2 else 0 }
    for (i in 0 until n - 1) {
        if (i == forkSegment) continue
        val dim = (model.dimRight && i >= you) || (model.dimLeft && i < you)
        drawLine(if (dim) colors.track else lineColor, Offset(x(i.toFloat()), mid), Offset(x(i + 1f), mid), lineW)
    }
    model.fork?.let { drawFork(it, lineColor, mid) }
    val fadeLen = 22.dp.toPx()
    if (model.fadeLeft) {
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, lineColor), startX = -fadeLen, endX = 0f), Offset(-fadeLen, mid - lineW / 2), Size(fadeLen, lineW))
    }
    if (model.fadeRight) {
        drawRect(Brush.horizontalGradient(listOf(lineColor, Color.Transparent), startX = w, endX = w + fadeLen), Offset(w, mid - lineW / 2), Size(fadeLen, lineW))
    }
}

/** How far the fork's two ends sit above and below the line. */
private fun DrawScope.forkSpread(mid: Float) = minOf(22.dp.toPx(), mid - 6.dp.toPx())

/** The fork's end stop index and the two branch end points. */
private fun DrawScope.forkEnds(fork: StripFork, mid: Float): List<Offset> {
    val end = if (fork.heading > 0) StripStopCount - 1 else 0
    val spread = forkSpread(mid)
    return listOf(Offset(stripX(end.toFloat()), mid - spread), Offset(stripX(end.toFloat()), mid + spread))
}

/** The line splitting in two after the last shared stop. */
private fun DrawScope.drawFork(fork: StripFork, color: Color, mid: Float) {
    val from = if (fork.heading > 0) StripStopCount - 2 else 1
    val start = Offset(stripX(from.toFloat()), mid)
    for (end in forkEnds(fork, mid)) {
        val path = Path().apply {
            moveTo(start.x, start.y)
            cubicTo((start.x + end.x) / 2, start.y, (start.x + end.x) / 2, end.y, end.x, end.y)
        }
        drawPath(path, color, style = Stroke(LineW.toPx(), cap = StrokeCap.Round))
    }
}

/** The stops other than yours (drawn last, see [drawStrip]); none under a marker at [markerXs]. */
private fun DrawScope.drawStops(model: StripModel, colors: StripColors, mid: Float, markerXs: List<Float>) {
    val n = StripStopCount
    val you = StripSideStops
    val r = DotRadius.toPx()
    val stroke = DotStroke.toPx()
    val reach = MarkerW.toPx() / 2 + Halo.toPx() + r
    val forkEnd = model.fork?.let { if (it.heading > 0) n - 1 else 0 }
    val line = (0 until n).filter { it != you && it != forkEnd }
        .map { i -> Offset(stripX(i.toFloat()), mid) to ((model.dimRight && i > you) || (model.dimLeft && i < you)) }
        .filter { (at, _) -> markerXs.none { abs(it - at.x) < reach } }
    val dots = line + model.fork?.let { f -> forkEnds(f, mid).map { it to false } }.orEmpty()
    for ((at, dim) in dots) {
        drawCircle(colors.card, r, at)
        drawCircle(if (dim) colors.track else colors.line, r - stroke / 2, at, style = Stroke(stroke))
    }
}

private fun DrawScope.drawMarkers(markers: List<Pair<StripVehicle, Float>>, colors: StripColors, mid: Float) {
    val r = MarkerW.toPx() / 2
    val halo = Halo.toPx()
    for ((v, cx) in markers) {
        val (fill, ink) = if (v.replacement) colors.bus to colors.busInk else colors.marker to colors.markerInk
        drawVehicleToken(
            center = Offset(cx, mid),
            radius = r,
            fill = fill,
            ink = ink,
            headingDeg = if (v.heading > 0) 90f else 270f,
            ring = colors.ring,
            gap = colors.card,
            gapWidth = halo,
            alpha = if (v.faded) 0.35f else 1f,
        )
    }
}

