package dev.southstar21.vectra.features.transit

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.graphicsLayer
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.MapColors
import dev.southstar21.vectra.core.maps.MapStop
import dev.southstar21.vectra.core.maps.MapStopKind
import dev.southstar21.vectra.core.maps.position
import org.maplibre.compose.map.MapState
import kotlin.math.abs
import kotlin.math.max

/*
 * The route map's entrance as its loading orb melts (see MapReveal). MapLibre doesn't take a
 * layer change every frame, so until the entrance ends this draws look-alikes over the map (the
 * line fading in, the stops popping out from yours, your location) and the real layers wait,
 * switching on just before the look-alikes go.
 */

/** Where the real layers take over: a few frames before the look-alikes leave, so nothing blinks. */
internal const val RevealHandOver = 0.97f

/** How long the look-alikes wait for the map to draw the real layers at most (a map that never idles). */
internal const val HandOverWaitMs = 700L

/** A route line as RouteLineLayers draws it: a casing under the line. */
@Immutable
internal class RevealLine(
    val points: List<LatLon>,
    val casing: Color,
    val casingWidth: Dp,
    val casingAlpha: Float,
    val ink: Color,
    val width: Dp,
    val alpha: Float,
)

/** One stop dot as RouteMap's `live-stops-*` layers draw it (MapLibre strokes outside the radius). */
private class StopLook(
    val radius: Dp,
    val fill: Color,
    val stroke: Color,
    val strokeWidth: Dp,
    val halo: Dp = 0.dp,
    val haloAlpha: Float = 0f,
    val haloRingAlpha: Float = 0f,
)

private fun lookOf(kind: MapStopKind?): StopLook = when (kind) {
    MapStopKind.Selected -> StopLook(6.5.dp, Palette.Blue500, Palette.Blue300, 2.5.dp, halo = 10.dp, haloAlpha = 0.25f, haloRingAlpha = 0.35f)
    MapStopKind.Tracked -> StopLook(5.5.dp, Palette.Blue600, Palette.Blue400, 2.dp, halo = 8.dp, haloAlpha = 0.12f, haloRingAlpha = 0.2f)
    MapStopKind.Terminus -> StopLook(5.dp, Palette.Zinc600, Palette.Zinc400, 1.dp)
    MapStopKind.Secondary -> StopLook(3.dp, Palette.Zinc700, Palette.Zinc500, 1.dp)
    MapStopKind.SecondaryTerminus -> StopLook(4.dp, Palette.Zinc700, Palette.Zinc500, 1.dp)
    else -> StopLook(4.dp, Palette.Zinc600, Palette.Zinc500, 1.dp)
}

/**
 * When each of the map's [count] stops pops (0 first, 1 last): the line's own stops outwards from
 * yours (selected, else tracked), then the other directions' stops, which follow [primary].
 */
internal fun stopRevealOrder(primary: List<RouteMapStop>, count: Int): FloatArray {
    val from = primary.indexOfFirst { it.isSelected == true }.takeIf { it >= 0 }
        ?: primary.indexOfFirst { it.isTracked }.takeIf { it >= 0 }
        ?: 0
    val span = max(1, max(from, primary.lastIndex - from))
    return FloatArray(count) { i -> if (i < primary.size) abs(i - from).toFloat() / span else 1f }
}

/** The look-alikes, drawn until [reveal] completes. Put it under the vehicle markers. */
@Composable
internal fun BoxScope.RouteReveal(
    reveal: MapReveal,
    lines: List<RevealLine>,
    stops: List<MapStop>,
    order: FloatArray,
    userLocation: LatLon?,
    map: () -> MapState?,
) {
    // Fades out once the map has drawn the real layers, so any small mismatch melts instead of flashing.
    val fade by animateFloatAsState(if (reveal.done) 0f else 1f, tween(180, easing = VectraEasing.Fade), label = "handOver")
    if (reveal.done && fade <= 0f) return
    val looks = remember(stops) { stops.map { lookOf(it.kind) } }
    val density = LocalDensity.current.density
    // The line runs on far past the map: keep it inside.
    // Stops and your location, drawn here or (while it melts) over the loading orb's dots.
    val marks: DrawScope.(Float) -> Unit = remember(stops, order, userLocation, looks) {
        { p -> map()?.let { m -> drawMarks(m, p, stops, looks, order, userLocation) } }
    }
    DisposableEffect(reveal, marks) {
        reveal.drawMarks = marks
        onDispose { if (reveal.drawMarks === marks) reveal.drawMarks = null }
    }
    Canvas(Modifier.fillMaxSize().clipToBounds().graphicsLayer { alpha = fade }) {
        val m = map() ?: return@Canvas
        val p = reveal.progress
        val lineA = revealLine(p)
        if (lineA > 0.01f) for (ln in lines) drawRevealLine(ln, routeScreenPath(m, ln.points, density), lineA)
        if (!reveal.orbDrawsMarks) marks(p)
    }
}

private fun DrawScope.drawMarks(m: MapState, p: Float, stops: List<MapStop>, looks: List<StopLook>, order: FloatArray, you: LatLon?) {
    for (i in stops.indices) {
        val s = revealStop(p, order.getOrElse(i) { 1f })
        if (s > 0.01f) project(m, stops[i].lat, stops[i].lon)?.let { drawStop(looks[i], it, s) }
    }
    val pop = revealLocation(p)
    if (you != null && pop > 0.01f) project(m, you.lat, you.lon)?.let { drawLocation(it, pop) }
}

private fun DrawScope.project(m: MapState, lat: Double, lon: Double): Offset? =
    runCatching { m.screenLocationFromPosition(LatLon(lat, lon).position) }.getOrNull()?.let { Offset(it.x.toPx(), it.y.toPx()) }

private fun DrawScope.drawRevealLine(ln: RevealLine, points: List<Offset>, a: Float) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }
    drawPath(path, ln.casing.copy(alpha = ln.casingAlpha * a), style = Stroke(ln.casingWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, ln.ink.copy(alpha = ln.alpha * a), style = Stroke(ln.width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawStop(look: StopLook, c: Offset, s: Float) {
    if (look.haloAlpha > 0f) {
        drawCircle(Palette.Blue500.copy(alpha = look.haloRingAlpha), radius = (look.halo + 1.dp).toPx() * s, center = c)
        drawCircle(Palette.Blue500.copy(alpha = look.haloAlpha), radius = look.halo.toPx() * s, center = c)
    }
    drawCircle(look.stroke, radius = (look.radius + look.strokeWidth).toPx() * s, center = c)
    drawCircle(look.fill, radius = look.radius.toPx() * s, center = c)
}

/** As UserLocationLayers: a soft halo and a white-ringed core. */
private fun DrawScope.drawLocation(c: Offset, s: Float) {
    drawCircle(Palette.Blue400.copy(alpha = 0.3f), radius = 10.dp.toPx() * s, center = c)
    drawCircle(Color.White, radius = 9.dp.toPx() * s, center = c)
    drawCircle(MapColors.Location, radius = 7.dp.toPx() * s, center = c)
}
