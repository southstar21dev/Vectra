package dev.southstar21.vectra.features.transit.strip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.width
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
import dev.southstar21.vectra.features.transit.RouteBadge
import dev.southstar21.vectra.features.transit.RouteBadgeSize
import dev.southstar21.vectra.features.transit.data.TransitRepository
import dev.southstar21.vectra.features.transit.data.TransitTracker
import dev.southstar21.vectra.features.transit.formatRouteDisplayName
import dev.southstar21.vectra.features.transit.getTrackerStops
import dev.southstar21.vectra.features.transit.nearby.rememberCatalogStopsById
import dev.southstar21.vectra.features.transit.rememberNowMillis
import dev.southstar21.vectra.features.transit.rememberOnline

private val SlimCardShape = RoundedCornerShape(18.dp)

/** Room for the widest usual badge ("200E", "M3"), so every slim card lines up. */
internal val SlimBadgeSlot = 48.dp

/** One headsign row of a card: its arrow, vehicle, name and its times on one line at the end. */
internal data class SlimDirectionEntry(
    val arrowRight: Boolean,
    val destination: String,
    val vehicle: StripVehicle,
    val nextTimes: List<StripTime>,
    /** Replacement buses going the other way from the direction picked. */
    val other: Boolean = false,
)

/** The later times each row lists after its next one. */
internal const val SlimLaterColumns = 2

/**
 * The rows a card lists under its strip: the two directions it draws, then any further heading
 * ([extra]) laid out the same way. [showNext] adds each row's later times.
 */
internal fun slimDirectionEntries(model: StripModel, extra: List<StripExtraRow> = emptyList(), showNext: Boolean = false): List<SlimDirectionEntry> {
    val right = model.vehicles.firstOrNull { it.heading > 0 }
    val left = model.vehicles.firstOrNull { it.heading < 0 }
    fun later(times: List<StripTime>) = if (showNext) times.drop(1).take(SlimLaterColumns) else emptyList()
    return buildList {
        if (right?.label != null && model.rightEnd != null) add(SlimDirectionEntry(true, model.rightEnd, right, later(model.rightTimes), model.rightOther))
        if (left?.label != null && model.leftEnd != null) add(SlimDirectionEntry(false, model.leftEnd, left, later(model.leftTimes), model.leftOther))
        // A line that forks past an end: its second destination is a row like the others.
        val fork = model.fork
        val forkFirst = model.forkTimes.firstOrNull()
        if (fork != null && forkFirst != null) {
            val vehicle = StripVehicle(0f, fork.heading, forkFirst.label, kind = forkFirst.kind, arriving = forkFirst.label == "NOW")
            add(SlimDirectionEntry(fork.heading > 0, fork.destination, vehicle, later(model.forkTimes)))
        }
        for (e in extra) add(SlimDirectionEntry(true, e.destination, e.vehicle, if (showNext) e.later else emptyList()))
    }
}

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
    /** Headings past the two the strip draws, listed like the rows above. */
    extra: List<StripExtraRow> = emptyList(),
) {
    val c = Vectra.colors
    val entries = slimDirectionEntries(model, extra, showNext)
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

private val FirstTimeColumn = 26.dp
private val LaterTimeColumn = 24.dp

/**
 * The arrow and name, then the times on one line ending at the row's right edge: a row with
 * fewer later times than another leaves no empty columns behind it.
 */
@Composable
private fun SlimDirectionRow(entry: SlimDirectionEntry) {
    val c = Vectra.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(if (entry.arrowRight) RemixIcons.ArrowRightLine else RemixIcons.ArrowLeftLine, size = 12.dp, tint = directionArrowTint(c))
        if (entry.vehicle.replacement) VIcon(RemixIcons.BusLine, size = 12.dp, tint = BusBlue)
        Column(Modifier.weight(1f)) {
            VText(entry.destination, Modifier.fillMaxWidth(), vtext(12.sp to 16.sp, Weight.Medium), c.textSecondary, maxLines = 2)
            if (entry.other) OtherDirectionNote()
        }
        Row(Modifier.widthIn(min = FirstTimeColumn), horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (entry.vehicle.kind == StripTimeKind.Scheduled && !entry.vehicle.faded && entry.vehicle.label != null) {
                VIcon(RemixIcons.TimeLine, size = 11.dp, tint = stripTimeColor(entry.vehicle))
            }
            VText(
                entry.vehicle.label.orEmpty(),
                style = vtext(13.sp to 17.sp, Weight.Extrabold, mono = true).tnum(),
                color = stripTimeColor(entry.vehicle),
                maxLines = 1,
                align = TextAlign.End,
            )
        }
        for (later in entry.nextTimes) {
            Box(Modifier.width(LaterTimeColumn), contentAlignment = Alignment.CenterEnd) {
                VText(later.label, style = vtext(11.sp to 15.sp, Weight.Medium, mono = true).tnum(), color = c.textMuted, maxLines = 1, softWrap = false, align = TextAlign.End)
            }
        }
    }
}

/**
 * The slim card the dashboard switches to with four or five pinned trackers: badge, stop,
 * a short strip and labelled direction times underneath.
 */
@Composable
internal fun LineStripSlimCard(
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
    val offline = !rememberOnline(repo.online)
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

/** Quiet note under a direction whose replacement buses go the other way from the one picked. */
@Composable
internal fun OtherDirectionNote(modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    VText("Other direction", modifier, vtext(11.sp to 15.sp, Weight.Semibold), Vectra.colors.tints.amber.text, align = align, maxLines = 1)
}
