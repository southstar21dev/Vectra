package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clipToBounds
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
import dev.southstar21.vectra.core.designsystem.components.SleepingIcon
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

/** Room for the widest usual badge ("200E", "M3"), so every slim card lines up. */
internal val SlimBadgeSlot = 48.dp

private data class SlimDirectionEntry(
    val arrowRight: Boolean,
    val destination: String,
    val vehicle: StripVehicle,
    val nextTimes: List<StripTime>,
)

/**
 * Compact full-width direction rows for multi-stop and slim cards: pairs each heading arrow with
 * its terminus name and soonest departure time so users never have to guess which arrow goes where.
 */
@Composable
internal fun SlimDirectionRows(
    model: StripModel,
    modifier: Modifier = Modifier,
    fallback: String? = null,
    showNext: Boolean = false,
) {
    val c = Vectra.colors
    val right = model.vehicles.firstOrNull { it.heading > 0 }
    val left = model.vehicles.firstOrNull { it.heading < 0 }
    val entries = buildList {
        if (right?.label != null && model.rightEnd != null) {
            add(SlimDirectionEntry(true, model.rightEnd, right, if (showNext) model.rightTimes.drop(1).take(2) else emptyList()))
        }
        if (left?.label != null && model.leftEnd != null) {
            add(SlimDirectionEntry(false, model.leftEnd, left, if (showNext) model.leftTimes.drop(1).take(2) else emptyList()))
        }
    }
    if (entries.isEmpty() && fallback == null) return
    Column(modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (entries.isEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                VText(fallback.orEmpty(), style = vtext(13.sp to 17.sp, Weight.Bold), color = c.textMuted)
            }
        } else {
            for (entry in entries) SlimDirectionRow(entry)
        }
    }
}

@Composable
private fun SlimDirectionRow(entry: SlimDirectionEntry) {
    val c = Vectra.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(if (entry.arrowRight) RemixIcons.ArrowRightLine else RemixIcons.ArrowLeftLine, size = 12.dp, tint = c.textMuted)
        if (entry.vehicle.replacement) VIcon(RemixIcons.BusLine, size = 12.dp, tint = BusBlue)
        VText(entry.destination, Modifier.weight(1f), vtext(12.sp to 16.sp, Weight.Medium), c.textSecondary, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            VText(
                entry.vehicle.label.orEmpty(),
                style = vtext(13.sp to 17.sp, Weight.Extrabold, mono = true).tnum(),
                color = stripTimeColor(entry.vehicle),
                maxLines = 1,
                align = TextAlign.End,
            )
            if (entry.nextTimes.isNotEmpty()) {
                VText(
                    entry.nextTimes.joinToString(" ") { it.label },
                    style = vtext(11.sp to 15.sp, Weight.Medium, mono = true).tnum(),
                    color = c.textMuted,
                    maxLines = 1,
                    align = TextAlign.End,
                )
            }
        }
    }
}

/**
 * The slim card the dashboard switches to with four or five pinned trackers: badge, stop,
 * a short strip and labelled direction times underneath.
 */
@Composable
fun LineStripSlimCard(
    repo: TransitRepository,
    tracker: TransitTracker,
    position: LatLon?,
    onOpen: (TransitTracker) -> Unit,
    modifier: Modifier = Modifier,
    /** Its place in the list: its entrance starts a beat after the cards above. */
    revealIndex: Int = 0,
) {
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
    val card = rememberDayColors().card
    val colors = stripColors(tracker.routeColor, tracker.routeTextColor, card, grey)
    val entrance = rememberStripEntrance(s.loading, revealIndex)
    val skeleton = stripColors(tracker.routeColor, tracker.routeTextColor, card, grey = true)
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
        // One width for every badge, so the cards' names and lines start in one column.
        Box(Modifier.widthIn(min = SlimBadgeSlot), contentAlignment = Alignment.CenterStart) {
            RouteBadge(formatRouteDisplayName(tracker.routeShortName, tracker.routeId), if (grey) null else tracker.routeColor, if (grey) null else tracker.routeTextColor, size = RouteBadgeSize.Sm)
        }
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VText(
                    stop.stopName,
                    Modifier.weight(1f),
                    vtext(13.sp to 17.sp, Weight.Bold),
                    if (s.noDepartures) c.textSecondary else c.textPrimary,
                    maxLines = 1,
                )
                if (s.noDepartures) SleepingIcon(size = 16.dp, tint = c.textMuted)
            }
            // Clipped: the line's fade past its ends stays out from under the badge and the times.
            LineStrip(s.model, colors, Modifier.clipToBounds(), labels = false, entrance = entrance, skeletonColors = skeleton)
            SlimDirectionRows(s.model, modifier = Modifier.padding(horizontal = 1.dp), showNext = true)
        }
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
