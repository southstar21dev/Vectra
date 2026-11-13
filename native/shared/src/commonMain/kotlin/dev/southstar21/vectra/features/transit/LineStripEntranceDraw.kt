package dev.southstar21.vectra.features.transit

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/* Drawing a tracker card's entrance (see LineStripEntrance.kt). */

/** The entrance: the grey strip shading into [colors], the vehicles springing from their placeholders. */
internal fun DrawScope.drawEntrance(model: StripModel, colors: StripColors, from: StripColors, e: StripEntrance, mid: Float, labels: Boolean) {
    val d = e.seconds
    val half = MarkerW.toPx() / 2
    val inset = EdgeInset.toPx()
    val x = { u: Float -> stripX(u) }
    if (e.skeleton) {
        drawStripParts(model, from, mid, emptyList(), hideAt = skeletonGhostXs(x)) { drawSkeletonGhosts(mid, half, x) }
    } else {
        val moving = model.vehicles.mapIndexedNotNull { i, v ->
            if (!v.showMarker) return@mapIndexedNotNull null
            val a = entranceVehicle(d, i, v.heading, v.position)
            Mark(v, stripX(a.position).coerceIn(half - inset, size.width - half + inset), a.scale, a.alpha) to a.ghost
        }
        val ghosts = moving.filter { it.second > 0.05f }.map { it.first.x }
        drawStripParts(model, lerpStrip(from, colors, entranceTint(d)), mid, moving.map { it.first }.filter { it.alpha > 0.05f }, hideAt = ghosts) {
            for ((m, ghost) in moving) drawGhost(Offset(m.x, mid), half, ghost)
        }
    }
    if (labels) drawPlaceholderPills(d, mid, x, e.skeleton)
}

/** [from] (the grey strip) shading into [to] by [t]. */
internal fun lerpStrip(from: StripColors, to: StripColors, t: Float): StripColors = StripColors(
    line = lerp(from.line, to.line, t),
    marker = to.marker,
    markerInk = to.markerInk,
    card = to.card,
    you = lerp(from.you, to.you, t),
    track = lerp(from.track, to.track, t),
    bus = to.bus,
    busInk = to.busInk,
    ring = to.ring,
)

/** A grey disc where a vehicle will be, [alpha] of it showing. */
internal fun DrawScope.drawGhost(center: Offset, radius: Float, alpha: Float) {
    if (alpha > 0.01f) drawCircle(GhostInk.copy(alpha = 0.22f * alpha), radius, center)
}

/** The skeleton's placeholders: a vehicle each side of your stop. */
internal fun DrawScope.drawSkeletonGhosts(mid: Float, radius: Float, x: (Float) -> Float) {
    drawGhost(Offset(x(GhostLeft), mid), radius, 1f)
    drawGhost(Offset(x(GhostRight), mid), radius, 1f)
}

/** Where the skeleton's placeholders sit, in px. */
internal fun skeletonGhostXs(x: (Float) -> Float): List<Float> = listOf(x(GhostLeft), x(GhostRight))

/** Grey pills where the times will go (above the one heading right, below the one heading left), fading at [d]. */
internal fun DrawScope.drawPlaceholderPills(d: Float, mid: Float, x: (Float) -> Float, skeleton: Boolean) {
    val a = if (skeleton) 1f else pillAlpha(d)
    if (a <= 0.01f) return
    val size = Size(30.dp.toPx(), 14.dp.toPx())
    val corner = CornerRadius(7.dp.toPx())
    val color: Color = GhostInk.copy(alpha = 0.18f * a)
    drawRoundRect(color, Offset(x(GhostLeft) - size.width / 2, mid - 37.dp.toPx()), size, corner)
    drawRoundRect(color, Offset(x(GhostRight) - size.width / 2, mid - 37.dp.toPx()), size, corner)
}
