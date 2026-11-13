package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.specularBorder
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.haversineMeters
import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.features.schedule.rememberDayColors
import kotlinx.coroutines.delay

internal val StripCardShape = RoundedCornerShape(22.dp)

/** A stop still loading after this long stops holding up the not-running stack. */
private const val SettleAfterMs = 8_000L

/** The schedule's day-card surface: opaque, a soft drop shadow, a lit top edge. */
@Composable
internal fun Modifier.stripCardSurface(shape: Shape = StripCardShape): Modifier {
    val d = rememberDayColors()
    return this.boxShadow(shape, d.cardShadow).background(d.card, shape).specularBorder(shape, d.border, d.borderTop)
}

/** One tracked stop's strip and its state. */
@Immutable
internal class StopStrip(
    val stop: TrackerStop,
    val model: StripModel,
    /** No arrivals have loaded yet. */
    val loading: Boolean,
    /** The feed failed and there is nothing to show. */
    val error: Boolean,
    /** Loaded, and nothing is coming. */
    val noDepartures: Boolean,
    val alerts: List<TransitAlert>,
    /** Directions past the two the strip draws (a line that forks). */
    val extra: List<StripDirection>,
    /** Waiting for times it can stand behind (not while offline). */
    val pending: Boolean,
    val now: Long,
) {
    /** Replacement buses stand in for the line (an alert says so). */
    val replaced: Boolean get() = alerts.any(::isReplacementAlert)

    /** Something is coming, or buses run in the line's place: not a line that's off for now. */
    val running: Boolean get() = !noDepartures || replaced
}

@Composable
internal fun rememberStopStrip(repo: TransitRepository, tracker: TransitTracker, stops: List<TrackerStop>, stop: TrackerStop, now: Long, offline: Boolean): StopStrip {
    val live = rememberLiveRoute(repo, tracker, stops, stop, now)
    val q = live.arrivalsQuery
    val updatedAt = q.dataUpdatedAt.takeIf { it > 0 }
    val frozen = isArrivalsStale(updatedAt, now, offline)
    val metro = tracker.routeType == "SUBWAY"
    val routeVehicles = live.routeVehicles
    val departed = rememberDepartures(live.directions, updatedAt)
    val leftAt = departed.toMap()
    // Enriched: the metro, which reports no GPS, gets its timetable-estimated position.
    val dirs = remember(live.enriched, routeVehicles, leftAt) {
        live.enriched.map { it.toStripDirection(routeVehicles, leftAt[it.destination()]) }
    }
    val model = remember(dirs, now, metro, frozen) { buildStripModel(dirs, now, metro, frozen) }
    val alerts = remember(q.data, tracker, stop.stopId, now / 60_000) {
        parseAlerts(q.data, AlertContext(tracker.routeId, tracker.routeShortName, tracker.routeType, stop.stopId), now)
    }
    val loaded = q.data != null
    return StopStrip(
        stop = stop,
        model = model,
        loading = !loaded && !q.isError,
        error = !loaded && q.isError,
        noDepartures = loaded && live.arrivals.isEmpty(),
        alerts = alerts,
        extra = remember(dirs) { extraDirections(dirs) },
        pending = !offline && !q.isError && (!loaded || isArrivalsStale(updatedAt, now, false)),
        now = now,
    )
}

/**
 * When each direction's last vehicle left your stop (see [justDeparted]), keyed by destination;
 * entries older than [JustLeftMs] stop showing. [updatedAt] is when the arrivals were fetched.
 */
@Composable
private fun rememberDepartures(directions: List<DirectionView>, updatedAt: Long?): Map<String, Long> {
    val leads = remember { HashMap<String, SeenLead>() }
    val left = remember { mutableStateMapOf<String, Long>() }
    LaunchedEffect(directions) {
        val now = nowMs()
        for (d in directions) {
            val a = d.arrival ?: continue
            val dest = d.destination()
            val prev = leads[dest]
            if (prev != null && justDeparted(prev, a.tripId, now)) left[dest] = now
            leads[dest] = SeenLead(a.tripId, a.arrivalMs, updatedAt ?: 0L)
        }
    }
    return left
}

/** Longest walk worth showing: further than this, you aren't walking there. */
internal const val MaxShownWalkMinutes = 60

/** Walking time from [position] to [stop], from the stop catalog; null past [MaxShownWalkMinutes]. */
@Composable
internal fun rememberWalkMinutes(repo: TransitRepository, stop: TrackerStop, position: LatLon?): Int? {
    val coords = rememberCatalogStopsById(repo).data
    return remember(coords, stop.stopId, position) {
        val at = coords?.get(stop.stopId) ?: return@remember null
        position?.let { calculateWalkMinutes(haversineMeters(it, LatLon(at.lat, at.lon))) }?.takeIf { it <= MaxShownWalkMinutes }
    }
}

/**
 * A tracker as a line strip: the line through your stop with the vehicles coming each way.
 * A tracker with several stops gets one slim row per stop instead. The card opens the live
 * view of its stop; a long press opens the tracker's menu.
 */
@Composable
fun LineStripCard(
    repo: TransitRepository,
    tracker: TransitTracker,
    position: LatLon?,
    onOpen: (TransitTracker) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: ((TransitTracker) -> Unit)? = null,
    onHasDeparturesChange: ((String, Boolean) -> Unit)? = null,
    onPendingChange: ((String, Boolean) -> Unit)? = null,
    /** Asks for location when there is none (first card only). */
    onAskLocation: (() -> Unit)? = null,
) {
    val now = rememberNowMillis(5_000)
    val offline = !rememberOnline(repo.connectivity)
    val stops = remember(tracker) { getTrackerStops(tracker) }
    val strips = stops.map { stop -> key(stop.stopId) { rememberStopStrip(repo, tracker, stops, stop, now, offline) } }

    val loaded = strips.any { !it.loading && !it.error }
    val hasDepartures = strips.any { it.running && !it.loading && !it.error }
    val pending = strips.any { it.pending }
    val hasCb by rememberUpdatedState(onHasDeparturesChange)
    val pendingCb by rememberUpdatedState(onPendingChange)
    // A failed feed, or one still loading after a few seconds, counts as "keep showing it":
    // the not-running stack must not wait forever on a stop that never answers.
    val failed = strips.all { it.error }
    LaunchedEffect(tracker.id, loaded, hasDepartures, failed) {
        when {
            loaded -> hasCb?.invoke(tracker.id, hasDepartures)
            failed -> hasCb?.invoke(tracker.id, true)
            else -> {
                delay(SettleAfterMs)
                hasCb?.invoke(tracker.id, true)
            }
        }
    }
    LaunchedEffect(tracker.id, pending) { pendingCb?.invoke(tracker.id, pending) }

    val open = { stop: TrackerStop -> onOpen(tracker.copy(stopId = stop.stopId, stopName = stop.stopName, headsign = stop.headsign.orEmpty())) }
    val longPress = onLongPress?.let { { it(tracker) } }
    val shape = StripCardShape
    val first = strips.first()
    Column(
        modifier
            .fillMaxWidth()
            .stripCardSurface(shape)
            .clip(shape)
            .tappable(shape, role = null, onClickLabel = "Open live departures", onLongClick = longPress) { open(first.stop) }
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (stops.size > 1) {
            MultiStopBody(repo, tracker, strips, position, open)
        } else {
            val walk = rememberWalkMinutes(repo, first.stop, position)
            StripHeader(
                tracker,
                title = first.stop.stopName.ifEmpty { tracker.stopName },
                walk = walk,
                grey = first.noDepartures,
                alert = first.alerts.isNotEmpty(),
                replaced = first.replaced,
                onAskLocation = if (position == null) onAskLocation else null,
            )
            StopStripBody(repo, tracker, first)
        }
    }
}

@Composable
private fun StopStripBody(repo: TransitRepository, tracker: TransitTracker, s: StopStrip) {
    val card = rememberDayColors().card
    val grey = s.loading || s.error || s.noDepartures
    val colors = stripColors(tracker.routeColor, tracker.routeTextColor, card, grey)
    val description = remember(s.model) { stripDescription(s.model) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LineStrip(s.model, colors, Modifier.semantics { contentDescription = description })
        StripStatus(repo, tracker, s)
    }
}

@Composable
private fun StripHeader(
    tracker: TransitTracker,
    title: String,
    walk: Int?,
    grey: Boolean,
    alert: Boolean,
    onAskLocation: (() -> Unit)?,
    subtitle: String? = null,
    /** The alert is replacement buses: a bus in bus blue rather than a warning. */
    replaced: Boolean = false,
) {
    val c = Vectra.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RouteBadge(
            formatRouteDisplayName(tracker.routeShortName, tracker.routeId),
            if (grey) null else tracker.routeColor,
            if (grey) null else tracker.routeTextColor,
            size = RouteBadgeSize.Sm,
        )
        Column(Modifier.weight(1f)) {
            VText(title, style = vtext(17.sp to 22.sp, Weight.Bold), color = if (grey) c.textSecondary else c.textPrimary, maxLines = 2)
            when {
                subtitle != null -> VText(subtitle, style = vtext(13.sp to 18.sp, Weight.Medium), color = c.textSecondary, maxLines = 1)
                walk != null -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    VIcon(RemixIcons.WalkLine, size = 14.dp, tint = c.textSecondary)
                    VText("$walk min walk", style = vtext(13.sp to 18.sp, Weight.Medium), color = c.textSecondary, maxLines = 1)
                }
                onAskLocation != null -> Row(
                    Modifier.tappable(role = null, onClick = onAskLocation),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VIcon(RemixIcons.Focus3Line, size = 14.dp, tint = c.textSecondary)
                    VText("Turn on location for walking times", style = vtext(13.sp to 18.sp, Weight.Semibold), color = c.accentPrimary, maxLines = 1)
                }
            }
        }
        if (replaced) VIcon(RemixIcons.BusLine, size = 18.dp, tint = c.tints.sky.text, contentDescription = "Replacement buses")
        else if (alert) VIcon(RemixIcons.AlertLine, size = 18.dp, tint = c.tints.amber.text, contentDescription = "Service alert")
    }
}

/** Option A for several stops: one slim row per stop under a "3 stops" header. */
@Composable
private fun MultiStopBody(repo: TransitRepository, tracker: TransitTracker, strips: List<StopStrip>, position: LatLon?, open: (TrackerStop) -> Unit) {
    val ends = strips.firstOrNull { it.model.leftEnd != null || it.model.rightEnd != null }?.model
    val subtitle = ends?.let { listOfNotNull(it.leftEnd, it.rightEnd).joinToString(" ↔ ") }
    Column(Modifier.fillMaxWidth()) {
        StripHeader(tracker, "${strips.size} stops", walk = null, grey = strips.all { it.noDepartures }, alert = strips.any { it.alerts.isNotEmpty() }, onAskLocation = null, subtitle = subtitle, replaced = strips.any { it.replaced })
        strips.forEachIndexed { i, s -> StopRow(repo, tracker, s, position, divider = i > 0) { open(s.stop) } }
    }
}

/** One stop of a several-stop tracker: name, walk, a short strip and both times. */
@Composable
private fun StopRow(repo: TransitRepository, tracker: TransitTracker, s: StopStrip, position: LatLon?, divider: Boolean, onOpen: () -> Unit) {
    val c = Vectra.colors
    val d = rememberDayColors()
    val rule = d.border
    val walk = rememberWalkMinutes(repo, s.stop, position)
    val colors = stripColors(tracker.routeColor, tracker.routeTextColor, d.card, s.loading || s.error || s.noDepartures)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (divider) Modifier.drawBehind { drawLine(rule, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else Modifier)
            .tappable(role = null, highlight = false, onClickLabel = "Open ${s.stop.stopName}", onClick = onOpen)
            .padding(top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                VText(s.stop.stopName, Modifier.weight(1f, fill = false), vtext(14.sp to 18.sp, Weight.Bold), c.textPrimary, maxLines = 1)
                if (walk != null) {
                    VIcon(RemixIcons.WalkLine, size = 12.dp, tint = c.textSecondary)
                    VText("$walk min", style = vtext(12.sp to 16.sp, Weight.Medium), color = c.textSecondary, maxLines = 1)
                }
            }
            LineStrip(s.model, colors, labels = false)
        }
        SlimTimes(s.model, if (s.error) "—" else null)
    }
}
