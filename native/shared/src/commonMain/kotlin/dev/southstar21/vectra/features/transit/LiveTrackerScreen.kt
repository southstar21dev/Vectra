package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.data.QueryState
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.isValidLatLon
import dev.southstar21.vectra.core.maps.decodePolyline
import dev.southstar21.vectra.core.time.formatBudapestHm

/** Data landed and the latest fetch did not fail. */
internal val QueryState<*>.isSuccess: Boolean get() = data != null && !isError

/**
 * How far the live tracker's last snapshots can be trusted. Offline, or with no fresh data for
 * [ArrivalsStaleMs], the countdowns stop at the snapshot's time instead of running down to "NOW".
 */
internal data class LiveFreshness(
    /** The clock countdowns run against: the arrivals snapshot's time once it is stale. */
    val countdownNow: Long,
    val arrivalsStale: Boolean,
    val vehiclesStale: Boolean,
    val offline: Boolean,
)

internal fun liveFreshness(arrivalsUpdatedAt: Long, vehiclesUpdatedAt: Long, now: Long, offline: Boolean): LiveFreshness {
    // Nothing loaded yet has nothing to freeze.
    val arrivalsStale = arrivalsUpdatedAt > 0 && isArrivalsStale(arrivalsUpdatedAt, now, offline)
    return LiveFreshness(
        countdownNow = if (arrivalsStale) arrivalsStaleNow(arrivalsUpdatedAt, now, offline) else now,
        arrivalsStale = arrivalsStale,
        vehiclesStale = vehiclesUpdatedAt > 0 && isArrivalsStale(vehiclesUpdatedAt, now, offline),
        offline = offline,
    )
}

/** The note over the map's lower edge, or null for none. */
internal fun liveMapNotice(f: LiveFreshness, arrivalsUpdatedAt: Long, arrivalsUnavailable: Boolean, noDepartures: Boolean): String? = when {
    f.arrivalsStale && f.offline -> "Offline · departures as of ${formatBudapestHm(arrivalsUpdatedAt)}"
    f.arrivalsStale -> "Departure times delayed · as of ${formatBudapestHm(arrivalsUpdatedAt)}"
    arrivalsUnavailable -> "Departure times unavailable · showing route"
    noDepartures -> "No upcoming departures right now"
    else -> null
}

/** A trip entry's geometry, or the route-variant stand-in for it. */
private data class GeoEntry(val stopTimes: List<FutarTripStopTime>, val polyline: FutarPolyline?)

private fun FutarRouteVariant.toGeoEntry() = GeoEntry(stopIds.orEmpty().map { FutarTripStopTime(stopId = it) }, polyline)

/** The stop a live view opens on: [preferred] when the tracker has it, else its primary stop. */
internal fun liveStopIdFor(tracker: TransitTracker, preferred: String?): String {
    val stops = getTrackerStops(tracker)
    return preferred?.takeIf { p -> p.isNotEmpty() && stops.any { it.stopId == p } }
        ?: stops.firstOrNull { it.stopId == tracker.stopId }?.stopId?.takeIf { it.isNotEmpty() }
        ?: stops.firstOrNull()?.stopId?.takeIf { it.isNotEmpty() }
        ?: tracker.stopId
}

internal fun liveStopFor(tracker: TransitTracker, stops: List<TrackerStop>, stopId: String): TrackerStop =
    stops.firstOrNull { it.stopId == stopId } ?: stops.firstOrNull() ?: TrackerStop(tracker.stopId, tracker.stopName, tracker.headsign)

/** What the live screen and its full map both draw for one tracker at one stop. */
internal class LiveRoute(
    val arrivalsQuery: QueryState<FutarArrivalsResponse>,
    val vehiclesQuery: QueryState<FutarVehiclesForRouteResponse>,
    val arrivals: List<Arrival>,
    val directions: List<DirectionView>,
    /** [directions] with approximate metro positions and their progress. */
    val enriched: List<DirectionView>,
    val secondaryLines: List<RouteMapSecondaryLine>,
    val leadVehicles: List<RouteMapVehicle>,
    /** Every live vehicle on the route, and on its replacement-bus route while one runs. */
    val routeVehicles: List<FutarVehicle>,
    /** The geometry source failed with nothing to render. */
    val feedError: Boolean,
    val routeColor: String,
)

/**
 * The tracked trips at [activeStop]: one direction per tracked trip (or the route's variants
 * when nothing is departing), with live — or, on the metro, schedule-approximated — vehicles.
 */
@Composable
internal fun rememberLiveRoute(repo: TransitRepository, tracker: TransitTracker?, trackerStops: List<TrackerStop>, activeStop: TrackerStop?, now: Long): LiveRoute {
    val arrivalsQuery = repo.arrivals.collect(activeStop?.stopId?.takeIf { it.isNotEmpty() })
    val vehiclesQuery = repo.vehicles.collect(tracker?.routeId?.takeIf { it.isNotEmpty() })

    // Next arrival per tracked direction → the trips we're following.
    val arrivals = remember(arrivalsQuery.data, tracker, activeStop) {
        if (tracker == null || activeStop == null) emptyList()
        else parseStopArrivals(arrivalsQuery.data, tracker.routeId, activeStop.headsign?.takeIf { it.isNotEmpty() }, shortName = tracker.routeShortName)
    }
    // While replacement buses stand in for the line, their positions come from their own route.
    val replacementQuery = repo.vehicles.collect(remember(arrivals) { arrivals.firstOrNull { it.isReplacementBus }?.routeId })
    val routeVehicles = remember(vehiclesQuery.data, replacementQuery.data) {
        vehiclesQuery.data?.data?.list.orEmpty() + replacementQuery.data?.data?.list.orEmpty()
    }
    val tripIds = remember(arrivals) { trackedTripIds(arrivals) }
    val vehicles = remember(routeVehicles, tripIds) { matchingVehicles(routeVehicles, tripIds) }

    // All directions are tracked; the LEAD one (map emphasis + first card) is
    // the one with a live vehicle, else the soonest arrival.
    val hasTrip = tripIds.isNotEmpty()
    val leadTripId = remember(tripIds, vehicles) {
        if (tripIds.size <= 1) tripIds.firstOrNull()
        else tripIds.firstOrNull { id -> vehicles.any { it.tripId == id } } ?: tripIds[0]
    }
    val otherTripId = if (tripIds.size > 1) tripIds.firstOrNull { it != leadTripId } else null
    val thirdTripId = if (tripIds.size > 2) tripIds.firstOrNull { it != leadTripId && it != otherTripId } else null

    // Geometry: the tracked trips, with the route's variants as the fallback
    // (no departure, trip-details missing, …). Each trip is asked for on its own service day.
    fun tripKey(tripId: String?) = if (hasTrip) tripDetailsKey(tripId, arrivals.firstOrNull { it.tripId == tripId }?.serviceDate) else null
    val tripQueryA = repo.tripDetails.collect(tripKey(leadTripId))
    val tripQueryB = repo.tripDetails.collect(tripKey(otherTripId))
    val tripQueryC = repo.tripDetails.collect(tripKey(thirdTripId))
    val routeQuery = repo.routeDetails.collect(tracker?.routeId?.takeIf { it.isNotEmpty() })
    val stopRefs = routeQuery.data?.data?.references?.stops.orEmpty()

    // Direction-aware fallback geometry for an arrival's headsign.
    fun variantEntryForArrival(arr: Arrival?, fallbackIdx: Int = 0): GeoEntry? {
        val variants = routeQuery.data?.data?.entry?.variants.orEmpty()
        if (variants.isEmpty()) return null
        val refs = stopRefs
        val trackedId = activeStop?.stopId?.takeIf { it.isNotEmpty() } ?: tracker?.stopId?.takeIf { it.isNotEmpty() }
        fun servesTracked(v: FutarRouteVariant): Boolean {
            val ids = v.stopIds.orEmpty()
            return trackedId in ids || ids.any { refs[it]?.parentStationId == trackedId }
        }
        if (arr != null) {
            fun matches(v: FutarRouteVariant) =
                isHeadsignMatch(v.headsign, arr.headSign, arr.tripHeadsign) ||
                    (!v.name.isNullOrEmpty() && isHeadsignMatch(v.name, arr.headSign, arr.tripHeadsign))
            variants.firstOrNull { v -> matches(v) && (trackedId == null || servesTracked(v)) }?.let { return it.toGeoEntry() }
            variants.firstOrNull { matches(it) }?.let { return it.toGeoEntry() }
        }
        if (trackedId != null) {
            val matching = variants.filter(::servesTracked)
            if (matching.isNotEmpty()) return matching[fallbackIdx % matching.size].toGeoEntry()
        }
        return (variants.getOrNull(fallbackIdx % variants.size) ?: variants[0]).toGeoEntry()
    }

    val trackedStopIdsSet = remember(trackerStops) { trackerStops.map { it.stopId }.toSet() }

    // One DirectionView per tracked direction (or the route fallback).
    val directions: List<DirectionView> = remember(
        hasTrip, leadTripId, otherTripId, thirdTripId, tripQueryA.data, tripQueryB.data, tripQueryC.data,
        routeQuery.data, activeStop, vehicles, arrivals, trackedStopIdsSet,
    ) {
        val out = mutableListOf<DirectionView>()
        fun push(entry: GeoEntry?, refs: Map<String, FutarStop>, tripId: String?, dirVehicles: List<RouteMapVehicle>, arrival: Arrival?) {
            val stopTimes = entry?.stopTimes.orEmpty()
            if (stopTimes.isEmpty()) return
            val trackedIndex = resolveTrackedStopIndex(stopTimes.map { it.stopId }, refs, activeStop?.stopId)
            val stops = stopTimes.mapIndexed { i, st ->
                val ref = refs[st.stopId]
                val isThisTracked = st.stopId in trackedStopIdsSet ||
                    (ref?.parentStationId != null && ref.parentStationId in trackedStopIdsSet)
                RouteMapStop(
                    name = ref?.name ?: st.stopHeadsign ?: st.stopId,
                    lat = ref?.lat ?: 0.0,
                    lon = ref?.lon ?: 0.0,
                    isTracked = isThisTracked || i == trackedIndex,
                    isSelected = i == trackedIndex,
                )
            }
            val polyline = entry?.polyline?.points?.takeIf { it.isNotEmpty() }?.let(::decodePolyline).orEmpty()
            out += DirectionView(
                tripId = tripId,
                stops = stops,
                polyline = polyline,
                vehicles = dirVehicles,
                arrival = arrival,
                upcoming = followingArrivals(arrivals, arrival, 3),
                trackedIndex = trackedIndex,
                progress = routeProgress(stops.map { LatLon(it.lat, it.lon) }, polyline, dirVehicles.firstOrNull()?.location, trackedIndex),
                stopTimes = stopTimes,
            )
        }
        // A vehicle assigned to the trip but without a GPS position has nowhere to be drawn: as (0,0)
        // it would project onto a line end and freeze a bogus stops-left count.
        fun vehiclesFor(tripId: String) = vehicles.filter { it.tripId == tripId && isValidLatLon(it.location) }
            .map { RouteMapVehicle(it.vehicleId, it.location!!, bearing = it.bearing) }
        fun tripEntry(q: QueryState<FutarTripDetailsResponse>): GeoEntry? =
            q.data?.data?.entry?.let { GeoEntry(it.stopTimes.orEmpty(), it.polyline) }?.takeIf { it.stopTimes.isNotEmpty() }

        if (hasTrip) {
            listOf(Triple(leadTripId, tripQueryA, 0), Triple(otherTripId, tripQueryB, 1), Triple(thirdTripId, tripQueryC, 2))
                .forEach { (id, q, idx) ->
                    if (id == null) return@forEach
                    val arr = arrivals.firstOrNull { it.tripId == id }
                    push(
                        tripEntry(q) ?: variantEntryForArrival(arr, idx),
                        q.data?.data?.references?.stops ?: stopRefs,
                        id,
                        vehiclesFor(id),
                        arr,
                    )
                }
        } else {
            val variants = routeQuery.data?.data?.entry?.variants.orEmpty()
            if (variants.size > 1) {
                for (idx in 0 until minOf(2, variants.size)) push(variants[idx].toGeoEntry(), stopRefs, null, emptyList(), null)
            } else {
                push(variantEntryForArrival(null, 0), stopRefs, null, emptyList(), null)
            }
        }
        out
    }

    // Approximate metro positions on a 10s clock (the vehicles poll cadence).
    val approxNowMs = now / 10_000 * 10_000
    val enriched = remember(directions, tracker?.routeType, approxNowMs) {
        directions.map { d ->
            val location = if (tracker?.routeType == "SUBWAY" && d.vehicles.isEmpty() && d.polyline.size >= 2) {
                approximateVehiclePosition(ApproximateVehicleInput(d.stopTimes, d.polyline, approxNowMs, 0))
            } else null
            val approxVehicle = location?.let { RouteMapVehicle(d.tripId ?: "approx", it, isApproximate = true) }
            d.copy(
                approxVehicle = approxVehicle,
                progress = routeProgress(
                    d.stops.map { LatLon(it.lat, it.lon) },
                    d.polyline,
                    approxVehicle?.location ?: d.vehicles.firstOrNull()?.location,
                    d.trackedIndex,
                ),
            )
        }
    }

    // Every direction beyond the lead, with all its vehicles.
    val secondaryLines = remember(enriched) {
        if (enriched.size < 2) emptyList()
        else enriched.drop(1).map { d ->
            RouteMapSecondaryLine(
                stops = d.stops,
                polyline = if (d.polyline.size >= 2) d.polyline else enriched[0].polyline,
                vehicles = d.approxVehicle?.let { d.vehicles + it } ?: d.vehicles,
            )
        }
    }

    val leadVehicles = remember(enriched) {
        enriched.firstOrNull()?.let { d -> d.approxVehicle?.let { d.vehicles + it } ?: d.vehicles }.orEmpty()
    }
    val feedError = (arrivalsQuery.isError && directions.isEmpty()) ||
        ((if (hasTrip) tripQueryA.isError else routeQuery.isError) && directions.isEmpty())
    return LiveRoute(
        arrivalsQuery = arrivalsQuery,
        vehiclesQuery = vehiclesQuery,
        arrivals = arrivals,
        directions = directions,
        enriched = enriched,
        secondaryLines = secondaryLines,
        leadVehicles = leadVehicles,
        routeVehicles = routeVehicles,
        feedError = feedError,
        routeColor = tracker?.routeColor?.takeIf { it.isNotEmpty() } ?: "3b82f6",
    )
}

/** A route badge beside the stop name: the live screens' bar title. */
@Composable
internal fun LiveTrackerTitle(tracker: TransitTracker, stopName: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RouteBadge(formatRouteDisplayName(tracker.routeShortName, tracker.routeId), tracker.routeColor, tracker.routeTextColor, size = RouteBadgeSize.Sm)
        VText(stopName, Modifier.weight(1f, fill = false), maxLines = 1)
    }
}
