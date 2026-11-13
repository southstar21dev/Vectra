package dev.southstar21.vectra.features.transit

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import dev.southstar21.vectra.core.designsystem.theme.VectraSprings
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.drawVehicleToken
import dev.southstar21.vectra.core.time.formatBudapestHm

/** Stops shown each side of yours before "Show all". */
private const val NearbyStops = 3

private val RowHeight = 40.dp
private val Gutter = 22.dp

/** Where each stop sits against the vehicle: already passed, the one it is at or just left, or ahead. */
private enum class StopState { Passed, Vehicle, Ahead }

/**
 * One direction's stops as a timeline: the line down the left in the route's colour (grey where
 * the vehicle has passed), the vehicle's token at the stop it is at or just left, your stop ringed
 * with a YOU tag, and each stop's timetable time. Collapsed, it shows the stretch from the vehicle
 * (or a few stops back) to a few past yours.
 */
@Composable
internal fun LiveStopTimeline(
    direction: DirectionView,
    colors: StripColors,
    showAll: Boolean,
    onToggleShowAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stops = direction.stops
    if (stops.isEmpty()) return
    val you = direction.trackedIndex.takeIf { it in stops.indices } ?: 0
    val vehicleRow = vehicleRow(direction)
    val collapsed = collapsedRows(stops.size, you, vehicleRow)
    val shown = if (showAll) stops.indices else collapsed
    val approx = direction.approxVehicle != null && direction.vehicles.isEmpty()
    // The rows beyond the collapsed stretch grow in and out instead of the list jumping.
    val enter = if (Vectra.reduceMotion) fadeIn(tween(150)) else expandVertically(VectraSprings.settle(), Alignment.Top) + fadeIn(tween(220, 60))
    val exit = if (Vectra.reduceMotion) fadeOut(tween(150)) else shrinkVertically(VectraSprings.settle(), Alignment.Top) + fadeOut(tween(140))

    Column(modifier.fillMaxWidth()) {
        for (i in stops.indices) {
            val row: @Composable () -> Unit = {
                TimelineRow(
                    name = stops[i].name,
                    time = stopClock(direction, i, you),
                    marker = TimelineMarker(
                        state = stopState(i, vehicleRow),
                        isYou = i == you,
                        lineAbove = i > shown.first,
                        lineBelow = i < shown.last,
                        approx = approx,
                    ),
                    colors = colors,
                )
            }
            if (i in collapsed) row() else AnimatedVisibility(showAll, enter = enter, exit = exit) { row() }
        }
        if (stops.size > collapsed.count() || showAll) {
            ShowAllButton(if (showAll) "Show fewer" else "Show all ${stops.size}", onToggleShowAll, Modifier.padding(top = 8.dp))
        }
    }
}

/** The row the vehicle is drawn on: it is between its last stop and its next ([RouteProgress.vehicleIndex]), shown at the last. -1 for none. */
private fun vehicleRow(direction: DirectionView): Int {
    val hasVehicle = direction.vehicles.isNotEmpty() || direction.approxVehicle != null
    val next = direction.progress.vehicleIndex
    return if (hasVehicle && next >= 0) (next - 1).coerceAtLeast(0) else -1
}

/** From the vehicle (one stop before it) or a few stops back, whichever is earlier, to a few past yours. */
private fun collapsedRows(count: Int, you: Int, vehicleRow: Int): IntRange {
    val from = minOf(you - NearbyStops, if (vehicleRow >= 0) vehicleRow - 1 else you).coerceAtLeast(0)
    return from..(you + NearbyStops).coerceAtMost(count - 1)
}

private fun stopState(i: Int, vehicleRow: Int): StopState = when {
    vehicleRow < 0 || i > vehicleRow -> StopState.Ahead
    i == vehicleRow -> StopState.Vehicle
    else -> StopState.Passed
}

/** Your stop shows the live arrival time; the others their timetable time. */
private fun stopClock(direction: DirectionView, i: Int, you: Int): String? {
    val live = direction.arrival?.arrivalMs?.takeIf { i == you && it > 0 }
    val st = direction.stopTimes.getOrNull(i)
    val ms = live ?: (st?.departureTime ?: st?.arrivalTime)?.times(1000)
    return ms?.let(::formatBudapestHm)
}

/** What the gutter draws for one row. */
private class TimelineMarker(val state: StopState, val isYou: Boolean, val lineAbove: Boolean, val lineBelow: Boolean, val approx: Boolean)

@Composable
private fun TimelineRow(name: String, time: String?, marker: TimelineMarker, colors: StripColors) {
    val c = Vectra.colors
    val passed = marker.state == StopState.Passed
    Row(Modifier.fillMaxWidth().height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(Gutter).fillMaxHeight()) { drawTimelineMarker(marker, colors) }
        Row(Modifier.weight(1f).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val weight = when {
                marker.isYou -> Weight.Bold
                marker.state == StopState.Vehicle -> Weight.Semibold
                else -> Weight.Medium
            }
            val ink = when {
                marker.isYou -> c.textPrimary
                passed -> c.textMuted
                else -> c.textPrimary.copy(alpha = 0.86f)
            }
            VText(name, Modifier.weight(1f, fill = false), vtext(14.sp to 18.sp, weight), ink, maxLines = 1)
            if (marker.isYou) VText("YOU", Modifier.padding(start = 6.dp), vtext(11.sp to 14.sp, Weight.Bold), c.tints.emerald.text)
        }
        if (time != null) {
            VText(time, Modifier.padding(start = 12.dp), vtext(13.sp to 16.sp, Weight.Semibold, mono = true).tnum(), if (passed) c.textMuted else c.textSecondary)
        }
    }
}

/** The line through the row (grey where the vehicle has been) and its dot, your ring, or the vehicle heading down the list. */
private fun DrawScope.drawTimelineMarker(m: TimelineMarker, colors: StripColors) {
    val at = Offset(size.width / 2, size.height / 2)
    val lineW = 3.dp.toPx()
    val above = if (m.state == StopState.Ahead) colors.line else colors.track
    val below = if (m.state == StopState.Passed) colors.track else colors.line
    if (m.lineAbove) drawLine(above, Offset(at.x, 0f), at, lineW)
    if (m.lineBelow) drawLine(below, at, Offset(at.x, size.height), lineW)
    when {
        m.state == StopState.Vehicle -> {
            // An estimated position (the metro, from the timetable) is drawn faded; a solid disc
            // under it, no wider than the token, keeps the line from showing through.
            if (m.approx) drawCircle(colors.card.copy(alpha = 1f), 11.dp.toPx(), at)
            drawVehicleToken(
                at, 11.dp.toPx(), colors.marker, colors.markerInk,
                // Straight on the line: a cut-out ring in the card's colour reads as a gap on the glass sheet.
                headingDeg = 180f, ring = colors.ring,
                alpha = if (m.approx) 0.7f else 1f,
            )
        }
        m.isYou -> {
            drawCircle(colors.line, 8.dp.toPx(), at)
            drawCircle(colors.you, 4.dp.toPx(), at)
        }
        else -> {
            val r = 4.5.dp.toPx()
            val ring = 2.5.dp.toPx()
            drawCircle(colors.card, r, at)
            drawCircle(if (m.state == StopState.Passed) colors.track else colors.line, r - ring / 2, at, style = Stroke(ring))
        }
    }
}

@Composable
private fun ShowAllButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    Box(
        modifier
            .height(36.dp)
            .background(c.textPrimary.copy(alpha = 0.08f), CircleShape)
            .tappable(CircleShape, pressedScale = 0.95f, haptic = { tick() }, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        VText(label, style = vtext(13.sp to 16.sp, Weight.Bold), color = c.textPrimary)
    }
}

/**
 * The timeline's colours: the line exactly as the route map draws it, so the list and the map
 * match. Only a near-black (night) line keeps the strip's lift, or it would vanish on the sheet.
 */
@Composable
internal fun timelineColors(routeColor: String, routeTextColor: String?, card: Color): StripColors {
    val base = stripColors(routeColor, routeTextColor, card)
    val route = parseHexColor(withHash(routeColor)) ?: return base
    return if (route.luminance() < 0.03f) base else base.withLine(route)
}

/** The tracked directions' names for the switch above the stops. */
internal fun directionLabel(d: DirectionView): String =
    d.arrival?.headSign?.takeIf { it.isNotEmpty() } ?: d.stops.lastOrNull()?.name.orEmpty()
