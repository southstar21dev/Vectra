package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.consumeTouches
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext

/*
 * The nearby map's departure chips (C1): a small glass tag over a stop with its next line and
 * how soon it leaves. Thinned nearest first so no two touch, and none covers you or the chrome.
 */

/** One stop's chip: where, and its soonest departure. */
@Immutable
data class NearbyChip(
    val stopId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Double,
    val walkMinutes: Int,
    val shortName: String,
    val color: String,
    val textColor: String,
    val countdown: Int,
    val isPredicted: Boolean,
)

/** Each stop's soonest departure in [feed], at the stop's place in [stops], nearest first. */
fun nearbyChips(feed: List<RadarDepartureItem>, stops: List<NearbyStop>): List<NearbyChip> {
    val byId = stops.associateBy { it.id }
    return feed.groupBy { it.stopId }.mapNotNull { (id, items) ->
        val stop = byId[id] ?: return@mapNotNull null
        val next = items.minBy { it.arrivalMs }
        NearbyChip(
            id, stop.name, stop.lat, stop.lon, next.distanceMeters, next.walkMinutes,
            next.shortName, next.color, next.textColor, next.countdownMinutes, next.isPredicted,
        )
    }.sortedBy { it.distanceMeters }
}

/** A chip's footprint on screen around its anchor (it hangs above its stop), in px. */
internal fun chipRect(anchor: Offset, w: Float, h: Float, gap: Float): Rect =
    Rect(anchor.x - w / 2, anchor.y - gap - h, anchor.x + w / 2, anchor.y - gap)

/**
 * Which [chips] to show: nearest first, each only where its footprint ([size] px, [gap] px above
 * its stop) clears the ones already kept and every [blocked] area. [project] puts a stop on
 * screen (null when it can't); [bounds] is the visible map.
 */
internal fun thinChips(
    chips: List<NearbyChip>,
    project: (NearbyChip) -> Offset?,
    w: Float,
    h: Float,
    gap: Float,
    bounds: Rect,
    blocked: List<Rect>,
    max: Int = 12,
): Set<String> {
    val kept = ArrayList<Rect>()
    val out = LinkedHashSet<String>()
    fun fits(r: Rect): Boolean {
        // A little air between chips, so they read as separate tags.
        val padded = r.inflate(4f)
        val inside = r.left >= bounds.left && r.right <= bounds.right && r.top >= bounds.top && r.bottom <= bounds.bottom
        return inside && blocked.none { it.overlaps(r) } && kept.none { it.overlaps(padded) }
    }
    for (chip in chips) {
        val r = project(chip)?.let { chipRect(it, w, h, gap) }
        if (out.size < max && r != null && fits(r)) {
            kept += r
            out += chip.stopId
        }
    }
    return out
}

/** The chip's size, for thinning. */
internal val ChipHeight = 28.dp
internal val ChipWidth = 66.dp
internal val ChipGap = 8.dp

/** The glass tag: the next line's badge and its time ([selected] outlined in the line's colour, [dimmed] faded). */
@Composable
internal fun DepartureChip(chip: NearbyChip, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, dimmed: Boolean = false) {
    val c = Vectra.colors
    val shape = RoundedCornerShape(10.dp)
    val ink = if (chip.isPredicted) c.tints.emerald.text else c.textPrimary
    Row(
        modifier
            .graphicsLayer { alpha = if (dimmed) 0.4f else 1f }
            .height(ChipHeight)
            .glass(shape)
            .then(if (selected) Modifier.border(1.5.dp, nearbyHexAlpha(chip.color, 0xFF), shape) else Modifier)
            .tappable(shape, role = Role.Button, onClickLabel = "Show ${chip.name}", haptic = { tick() }, onClick = onClick)
            .padding(start = 4.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteBadge(chip.shortName, chip.color, chip.textColor, size = RouteBadgeSize.Tag)
        VText(formatNearbyCountdown(chip.countdown), style = vtext(13.sp to 16.sp, Weight.Extrabold, mono = true), color = ink, maxLines = 1, softWrap = false)
    }
}

/**
 * A picked stop's board over the map: its name, then each line and direction with the
 * next three times. [onClose] lets the stop go.
 */
@Composable
internal fun StopBoard(stopName: String, groups: List<List<RadarDepartureItem>>, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.widthIn(max = 300.dp).glass(shape).consumeTouches().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VText(stopName, Modifier.weight(1f), vtext(TypeScale.Body, Weight.Bold), c.textPrimary, maxLines = 1)
            Box(
                Modifier.size(32.dp).tappable(CircleShape, role = Role.Button, onClickLabel = "Close $stopName", onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { VIcon(RemixIcons.CloseLine, size = 16.dp, tint = c.textSecondary) }
        }
        if (groups.isEmpty()) {
            VText("No departures soon", Modifier.padding(end = 8.dp, bottom = 2.dp), vtext(TypeScale.Caption), c.textMuted)
        }
        for (items in groups.take(BoardRows)) BoardRow(items)
    }
}

private const val BoardRows = 4

@Composable
private fun BoardRow(items: List<RadarDepartureItem>) {
    val c = Vectra.colors
    val first = items[0]
    Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.widthIn(min = 28.dp), contentAlignment = Alignment.CenterStart) {
            RouteBadge(first.shortName, first.color, first.textColor, size = RouteBadgeSize.TagMd)
        }
        VText(first.headsign.ifEmpty { "Any direction" }, Modifier.weight(1f), vtext(TypeScale.Caption, Weight.Semibold), c.textPrimary, maxLines = 1)
        // The next three, one line, each in its own column so every row's times line up.
        for (i in 0 until BoardTimes) {
            val d = items.getOrNull(i)
            VText(
                d?.let { formatNearbyCountdown(it.countdownMinutes) }.orEmpty(),
                Modifier.width(BoardTimeWidth),
                vtext(13.sp to 16.sp, if (i == 0) Weight.Extrabold else Weight.Semibold, mono = true),
                when {
                    d == null -> c.textMuted
                    i == 0 && d.isPredicted -> c.tints.emerald.text
                    i == 0 -> c.textPrimary
                    else -> c.textMuted
                },
                maxLines = 1,
                softWrap = false,
                align = TextAlign.End,
            )
        }
    }
}

/** Departures a board row shows. */
private const val BoardTimes = 3

/** One time's column: room for "59'" or "NOW", so the columns line up row to row. */
private val BoardTimeWidth: Dp = 34.dp

/** Groups [feed] by line, stop and direction, keeping the feed's order; each group's departures soonest first. */
internal fun groupDepartures(feed: List<RadarDepartureItem>): List<List<RadarDepartureItem>> {
    val groups = LinkedHashMap<String, MutableList<RadarDepartureItem>>()
    for (item in feed) groups.getOrPut("${item.routeId}|${item.stopId}|${item.headsign}") { mutableListOf() } += item
    return groups.values.map { it.sortedBy { d -> d.arrivalMs } }
}

/** How many of the nearest stops the Transit card polls for its chips. */
private const val PeekChipStops = 3

/** The Transit card's chips: the nearest few stops' next departures (polled only once you're located). */
@androidx.compose.runtime.Composable
internal fun rememberPeekChips(repo: TransitRepository, nearby: NearbyStopsState): List<NearbyChip> {
    val located = nearby.status == NearbyLocationStatus.Tracking && nearby.position != null
    val stops = androidx.compose.runtime.remember(nearby.stops, located) { if (located) nearby.stops.take(PeekChipStops) else emptyList() }
    val arrivals = rememberBatchStopArrivals(repo, stops.map { it.id })
    val routes = rememberCatalogRoutes(repo)
    val now = rememberNowMillis(30_000)
    return androidx.compose.runtime.remember(stops, arrivals, routes.data, now) {
        val boards = stops.associate { it.id to dropDepartedBoards(parseAllNearbyBoards(arrivals[it.id]?.data, routes.data), now) }
        nearbyChips(buildRadarDepartures(stops, boards, now), stops)
    }
}
