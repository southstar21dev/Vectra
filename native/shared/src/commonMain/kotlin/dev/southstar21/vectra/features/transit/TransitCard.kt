package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.SleepingIcon
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext

/** Tabular figures. */
internal fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

/**
 * One departure cell: a sleeping Zzz when nothing is coming; otherwise the time
 * behind a live-signal glyph (live) or a clock (timetable only).
 */
@Composable
fun MonitorTime(arrival: Arrival?, now: Long, frozen: Boolean, metro: Boolean, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    val ink = Vectra.monitor
    if (arrival == null) {
        Row(modifier, horizontalArrangement = Arrangement.End) { SleepingIcon(size = 16.dp, tint = ink.muted) }
        return
    }
    val minutes = minutesUntil(arrival.arrivalMs, now)
    // The metro feed carries no vehicle positions, but metro runs to its timetable closely enough to read as live.
    val trusted = arrival.isPredicted || metro
    val live = trusted && !frozen
    Row(
        modifier.alpha(if (frozen) 0.5f else 1f),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) LiveSignalIcon(size = 12.dp, tint = if (metro) c.tints.purple.text else c.tints.emerald.text)
        else VIcon(RemixIcons.TimeLine, size = 12.dp, tint = ink.muted)
        VText(
            formatMonitorTime(arrival.arrivalMs, now),
            style = vtext(17.sp to 17.sp, if (minutes <= 0) Weight.Medium else Weight.Extrabold, Tracking.Tight).tabular(),
            color = when {
                minutes < 2 && live -> c.tints.emerald.text
                live -> ink.live
                else -> ink.sched
            },
            maxLines = 1,
            softWrap = false,
        )
    }
}

private class MonitorPage(
    val stop: TrackerStop,
    val headsign: String?,
    val data: FutarArrivalsResponse?,
    val isError: Boolean,
    val dataUpdatedAt: Long,
    val arrivals: List<Arrival>,
)

/** The alert rubber stamp beside the line badge. */
@Composable
private fun AlertStamp(count: Int, onClick: () -> Unit) {
    val amber = Vectra.colors.tints.amber
    val shape = RoundedCornerShape(5.dp)
    Row(
        Modifier
            .rotate(-6f)
            .clip(shape)
            .background(amber.bg, shape)
            .border(1.5.dp, amber.text, shape)
            .tappable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(RemixIcons.AlertLine, size = 12.dp, tint = amber.text)
        VText(
            if (count > 1) "$count Alerts" else "Alert",
            style = vtext(11.sp to 11.sp, Weight.Extrabold, Tracking.Wide),
            color = amber.text,
            maxLines = 1,
        )
    }
}

/** The stop column + fixed direction columns (`minmax(0,1fr) repeat(n, w)`). */
@Composable
private fun MonitorGridRow(
    columns: Int,
    columnWidth: Dp,
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    first: @Composable RowScope.() -> Unit,
    cell: @Composable (Int) -> Unit,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = verticalAlignment) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = first)
        repeat(columns) { i -> Box(Modifier.width(columnWidth), contentAlignment = Alignment.CenterEnd) { cell(i) } }
    }
}

/**
 * A tracker as a stop display — the line badge on top,
 * directions as columns, every tracked stop as a row with its next departure
 * per direction. Each row opens the live dialog at that stop.
 */
@Composable
fun TransitCard(
    repo: TransitRepository,
    tracker: TransitTracker,
    onOpen: (TransitTracker) -> Unit,
    modifier: Modifier = Modifier,
    onHasDeparturesChange: ((String, Boolean) -> Unit)? = null,
    /** True while this tracker has no times it can stand behind yet. */
    onPendingChange: ((String, Boolean) -> Unit)? = null,
    /** The faint favourite star; off where every tracker is a favourite anyway. */
    showFavoriteMark: Boolean = true,
    /** Long-pressing the card (opens its action menu). */
    onLongPress: ((TransitTracker) -> Unit)? = null,
) {
    val longPress = onLongPress?.let { { it(tracker) } }
    val c = Vectra.colors
    val ink = Vectra.monitor
    // The cells show whole minutes; a coarse tick keeps the card from recomposing every second.
    val now = rememberNowMillis(5_000)
    val isOffline = !rememberOnline(repo.connectivity)
    val stops = remember(tracker) { getTrackerStops(tracker) }
    val stopIds = remember(stops) { stops.map { it.stopId } }
    val arrivalsMap = rememberMultiStopArrivals(repo, stopIds)

    val pages = remember(stops, arrivalsMap, tracker.routeId, tracker.headsign) {
        stops.map { stop ->
            val entry = arrivalsMap[stop.stopId]
            val headsign = stop.headsign?.takeIf { it.isNotEmpty() } ?: (if (stops.size == 1) tracker.headsign else "").takeIf { it.isNotEmpty() }
            MonitorPage(stop, headsign, entry?.data, entry?.isError ?: false, entry?.dataUpdatedAt ?: 0, parseStopArrivals(entry?.data, tracker.routeId, headsign))
        }
    }
    val grid = remember(pages) { buildMonitorGrid(pages.map { MonitorStopInput(it.stop.stopId, it.headsign, it.arrivals) }) }

    // Alerts stay attached to the tracker's primary stop.
    val primary = pages.firstOrNull()
    val alerts = remember(primary, tracker, now / 60_000) {
        parseAlerts(primary?.data, AlertContext(tracker.routeId, tracker.routeShortName, tracker.routeType, primary?.stop?.stopId ?: tracker.stopId), now)
    }

    val hasLoaded = pages.any { it.data != null }
    val hasUpcoming = pages.any { it.arrivals.isNotEmpty() }
    val hasDeparturesCb by rememberUpdatedState(onHasDeparturesChange)
    LaunchedEffect(tracker.id, hasLoaded, hasUpcoming) {
        if (hasLoaded) hasDeparturesCb?.invoke(tracker.id, hasUpcoming)
    }
    // Offline never counts as pending: the page would stay covered while the connection is gone.
    val pending = pages.any { !it.isError && (it.data == null || isArrivalsStale(it.dataUpdatedAt, now, false)) } && !isOffline
    val pendingCb by rememberUpdatedState(onPendingChange)
    LaunchedEffect(tracker.id, pending) { pendingCb?.invoke(tracker.id, pending) }

    val displayName = formatRouteDisplayName(tracker.routeShortName, tracker.routeId)
    // Two directions get room for their names; a third squeezes in narrower.
    val columnWidth = if (grid.columns.size >= 3) 72.dp else 88.dp
    val columnCount = maxOf(1, grid.columns.size)

    val openStop = { index: Int ->
        val stop = stops.getOrNull(index) ?: stops[0]
        onOpen(tracker.copy(stopId = stop.stopId, stopName = stop.stopName, headsign = stop.headsign.orEmpty()))
    }

    Box(
        modifier
            .fillMaxWidth()
            .monitorSurface()
            .clip(MonitorShape)
            .tappable(MonitorShape, onLongClick = longPress) { openStop(0) },
    ) {
        // Dashboard favourites carry a faint star stamped into the panel.
        if (tracker.isFavorite && showFavoriteMark) {
            VIcon(
                RemixIcons.StarFill,
                Modifier.align(Alignment.BottomStart).offset(x = (-12).dp, y = 12.dp).rotate(-12f).alpha(if (c.isDark) 0.1f else 0.08f),
                size = 64.dp,
                tint = ink.live,
            )
        }
        Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            // Header: the line (+ alert stamp) over the stop column, direction names over their time columns.
            MonitorGridRow(
                columns = grid.columns.size,
                columnWidth = columnWidth,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
                first = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RouteBadge(displayName, tracker.routeColor, tracker.routeTextColor, size = RouteBadgeSize.Sm)
                        if (alerts.isNotEmpty()) AlertStamp(alerts.size) { openStop(0) }
                    }
                },
                cell = { i ->
                    VText(
                        grid.columns[i],
                        Modifier.fillMaxWidth(),
                        vtext(TypeScale.Caption, Weight.Semibold, lineHeight = 15.sp),
                        ink.muted,
                        align = TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
            grid.rows.forEachIndexed { index, row ->
                val page = pages[index]
                val frozen = isArrivalsStale(page.dataUpdatedAt, now, isOffline)
                val cellNow = arrivalsStaleNow(page.dataUpdatedAt, now, isOffline)
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val rule = ink.rule
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(if (pressed) ink.pressed else Color.Transparent)
                        .drawBehind { drawLine(rule, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                        .tappable(highlight = false, interactionSource = interaction, onLongClick = longPress) { openStop(index) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (page.isError && page.data == null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FormattedRouteTitle(page.stop.stopName, Modifier.weight(1f), vtext(13.5.sp to 20.sp, Weight.Semibold), ink.text)
                            VText("Live feed unavailable", style = vtext(TypeScale.Caption, Weight.Semibold), color = c.tints.red.text, align = TextAlign.End, maxLines = 1)
                        }
                    } else {
                        MonitorGridRow(
                            columns = columnCount,
                            columnWidth = columnWidth,
                            first = { FormattedRouteTitle(page.stop.stopName, Modifier.fillMaxWidth(), vtext(13.5.sp to 20.sp, Weight.Semibold), ink.text) },
                            cell = { i ->
                                if (grid.columns.isEmpty()) MonitorTime(null, cellNow, frozen = false, metro = false, modifier = Modifier.fillMaxWidth())
                                else MonitorTime(row.cells[i], cellNow, frozen, tracker.routeType == "SUBWAY", Modifier.fillMaxWidth())
                            },
                        )
                    }
                }
            }
            if (grid.extraColumns > 0) {
                val rule = ink.rule
                VText(
                    "${grid.extraColumns} more direction${if (grid.extraColumns == 1) "" else "s"}, open a stop to see them",
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .drawBehind { drawLine(rule, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                        .padding(vertical = 8.dp),
                    vtext(TypeScale.Caption, Weight.Medium),
                    ink.muted,
                )
            }
        }
    }
}
