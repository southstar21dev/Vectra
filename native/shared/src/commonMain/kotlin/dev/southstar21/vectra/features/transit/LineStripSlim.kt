package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.haversineMeters
import dev.southstar21.vectra.features.schedule.rememberDayColors

private val SlimCardShape = RoundedCornerShape(18.dp)

/** The right-hand times of a slim strip: → next right-heading, ← next left-heading. */
@Composable
internal fun SlimTimes(model: StripModel, fallback: String? = null) {
    val c = Vectra.colors
    // At least 52 dp so the slim strips line up; a clock time ("13:42") may widen it.
    Column(Modifier.widthIn(min = 52.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val right = model.vehicles.firstOrNull { it.heading > 0 }
        val left = model.vehicles.firstOrNull { it.heading < 0 }
        if (right == null && left == null && fallback != null) {
            VText(fallback, style = vtext(14.sp to 18.sp, Weight.Bold), color = c.textMuted)
        }
        for ((v, icon) in listOf(right to RemixIcons.ArrowRightLine, left to RemixIcons.ArrowLeftLine)) {
            val label = v?.label ?: continue
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                VIcon(icon, size = 11.dp, tint = c.textMuted)
                VText(
                    label,
                    style = vtext(14.sp to 18.sp, Weight.Extrabold, mono = true).tnum(),
                    color = stripTimeColor(v),
                    maxLines = 1,
                    align = TextAlign.End,
                )
            }
        }
    }
}

/**
 * The slim card the dashboard switches to with four or five pinned trackers: badge, stop,
 * a short strip and both times at the right.
 */
@Composable
fun LineStripSlimCard(repo: TransitRepository, tracker: TransitTracker, position: LatLon?, onOpen: (TransitTracker) -> Unit, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    val now = rememberNowMillis(5_000)
    val offline = !rememberOnline(repo.connectivity)
    val stops = remember(tracker) { getTrackerStops(tracker) }
    // The stop nearest you, else the first.
    val coords = rememberCatalogStopsById(repo).data
    val stop = remember(stops, coords, position) {
        if (position == null || coords == null) stops.first()
        else stops.minByOrNull { s -> coords[s.stopId]?.let { haversineMeters(position, LatLon(it.lat, it.lon)) } ?: Double.MAX_VALUE } ?: stops.first()
    }
    val s = rememberStopStrip(repo, tracker, stops, stop, now, offline)
    val grey = s.loading || s.error || s.noDepartures
    val colors = stripColors(tracker.routeColor, tracker.routeTextColor, rememberDayColors().card, grey)
    val shape = SlimCardShape
    Row(
        modifier
            .fillMaxWidth()
            .stripCardSurface(shape)
            .clip(shape)
            .tappable(shape, role = null, onClickLabel = "Open live departures") { onOpen(tracker.copy(stopId = stop.stopId, stopName = stop.stopName, headsign = stop.headsign.orEmpty())) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteBadge(formatRouteDisplayName(tracker.routeShortName, tracker.routeId), if (grey) null else tracker.routeColor, if (grey) null else tracker.routeTextColor, size = RouteBadgeSize.Sm)
        Column(Modifier.weight(1f)) {
            VText(stop.stopName, style = vtext(13.sp to 17.sp, Weight.Bold), color = c.textPrimary, maxLines = 1)
            LineStrip(s.model, colors, labels = false)
        }
        SlimTimes(s.model, if (s.noDepartures) "—" else null)
    }
}

/** The strip in words, for screen readers: "to Széll Kálmán tér in 1 min, to Újbuda now". */
internal fun stripDescription(model: StripModel): String = model.vehicles.mapNotNull { v ->
    val dest = (if (v.heading > 0) model.rightEnd else model.leftEnd) ?: return@mapNotNull null
    val label = v.label
    val time = when {
        v.arriving || label == "NOW" -> "now"
        label == null -> return@mapNotNull null
        label.endsWith("'") -> "in ${label.dropLast(1)} min"
        else -> "at $label"
    }
    "to $dest $time"
}.joinToString(", ")
