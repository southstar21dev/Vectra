package dev.southstar21.vectra.features.transit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.haversineMeters
import dev.southstar21.vectra.core.time.nowMs

@Immutable
data class StopView(
    val stop: TrackerStop,
    val arrivals: List<Arrival>,
    val loading: Boolean,
    val error: Boolean,
    val updatedAt: Long?,
)

@Immutable
data class PinnedRow(
    val tracker: TransitTracker,
    /** Every stop of the tracker, in its saved order. */
    val views: List<StopView>,
    /** The stop shown in the row: the one nearest you, else the first. */
    val primary: Int,
    /** Catalog position of the primary stop, for the map. */
    val primaryAt: LatLon?,
    /** Next departure at the primary stop, for sorting. */
    val soonestMs: Long?,
)

/** The pinned-rows computation, separated from the queries (pure). */
fun buildPinnedRows(
    trackers: List<TransitTracker>,
    trackerStops: List<List<TrackerStop>>,
    arrivals: Map<String, StopArrivalsEntry>,
    coords: Map<String, CatalogStop>,
    position: LatLon?,
    now: Long = nowMs(),
): List<PinnedRow> {
    val rows = trackers.mapIndexed { index, tracker ->
        val stops = trackerStops[index]
        val views = stops.map { stop ->
            val query = arrivals[stop.stopId]
            StopView(
                stop = stop,
                arrivals = parseStopArrivals(
                    query?.data,
                    tracker.routeId,
                    stop.headsign?.takeIf { it.isNotEmpty() }
                        ?: (if (stops.size == 1) tracker.headsign.takeIf { it.isNotEmpty() } else null),
                    now,
                ),
                loading = (query?.isLoading ?: false) && query.data == null,
                error = query?.isError ?: false,
                updatedAt = query?.dataUpdatedAt?.takeIf { it > 0 },
            )
        }
        var primary = 0
        var primaryDistance: Double? = null
        if (position != null) {
            stops.forEachIndexed { i, stop ->
                val at = coords[stop.stopId] ?: return@forEachIndexed
                val distance = haversineMeters(position, LatLon(at.lat, at.lon))
                if (primaryDistance == null || distance < primaryDistance) {
                    primary = i
                    primaryDistance = distance
                }
            }
        }
        val first = views.getOrNull(primary)?.arrivals?.firstOrNull()
        val at = coords[stops.getOrNull(primary)?.stopId ?: ""]
        PinnedRow(tracker, views, primary, at?.let { LatLon(it.lat, it.lon) }, first?.arrivalMs)
    }
    // Stable: trackers without departures keep their saved order at the end.
    return rows.withIndex().sortedWith { a, b ->
        val sa = a.value.soonestMs
        val sb = b.value.soonestMs
        if (sa == null || sb == null) {
            if (sa == null) (if (sb == null) a.index - b.index else 1) else -1
        } else {
            sa.compareTo(sb).takeIf { it != 0 } ?: (a.index - b.index)
        }
    }.map { it.value }
}

/**
 * The dashboard's pinned trackers as rows — one arrivals query
 * per stop shared across every tracker, each tracker narrowed to the stop
 * nearest you, and the rows ordered by whichever departs first.
 */
@Composable
fun rememberPinnedRows(repo: TransitRepository, trackers: List<TransitTracker>, position: LatLon?): List<PinnedRow> {
    val catalogStops = rememberCatalogStopsById(repo)
    val trackerStops = remember(trackers) {
        trackers.map { tracker ->
            getTrackerStops(tracker).ifEmpty { listOf(TrackerStop(tracker.stopId, tracker.stopName, tracker.headsign)) }
        }
    }
    val stopIds = remember(trackerStops) { trackerStops.flatMap { stops -> stops.map { it.stopId } } }
    val arrivals = rememberMultiStopArrivals(repo, stopIds)
    val coords = catalogStops.data.orEmpty()
    return remember(trackers, trackerStops, arrivals, coords, position) {
        buildPinnedRows(trackers, trackerStops, arrivals, coords, position)
    }
}
