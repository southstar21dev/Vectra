package dev.southstar21.vectra.features.transit.live

import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.cumulativeHaversineDistances
import dev.southstar21.vectra.core.maps.cutPolylineAtDistance
import dev.southstar21.vectra.core.maps.makeProjector
import dev.southstar21.vectra.core.maps.nextStopIndex
import dev.southstar21.vectra.core.maps.pointAtFraction
import dev.southstar21.vectra.core.maps.projectVehicleOntoPolyline
import dev.southstar21.vectra.core.maps.snapToPolyline
import dev.southstar21.vectra.features.transit.data.Arrival
import dev.southstar21.vectra.features.transit.data.FutarStop
import dev.southstar21.vectra.features.transit.data.FutarTripStopTime
import dev.southstar21.vectra.features.transit.data.FutarVehicle
import kotlin.math.max
import kotlin.math.min

/** Result of [clipRouteToTrackedStop]. */
internal data class ClippedRoute<T>(val polyline: List<LatLon>, val stops: List<T>)

/**
 * The map window for one direction: polyline + stops from the line start up to (and
 * including) the tracked stop. Null when it can't be resolved (callers keep the full route).
 * `positionOf` reads a stop's coordinate.
 */
internal fun <T> clipRouteToTrackedStop(
    polyline: List<LatLon>,
    stops: List<T>,
    trackedIndex: Int,
    positionOf: (T) -> LatLon,
): ClippedRoute<T>? {
    if (polyline.size < 2 || stops.isEmpty()) return null
    val tracked = stops.getOrNull(trackedIndex)?.let(positionOf) ?: return null
    if (tracked.lat == 0.0 && tracked.lon == 0.0) return null
    val dists = cumulativeHaversineDistances(polyline)
    val total = dists[dists.size - 1]
    if (total.isNaN() || total <= 0) return null
    val snapped = snapToPolyline(LatLon(tracked.lat, tracked.lon), polyline) ?: return null
    if (snapped.distance < 1) return null
    val cut = cutPolylineAtDistance(polyline, dists, snapped.distance)
    if (cut.size < 2) return null
    return ClippedRoute(cut, stops.subList(0, min(stops.size, trackedIndex + 1)).toList())
}

/** Convenience overload for plain coordinates. */
internal fun clipRouteToTrackedStop(polyline: List<LatLon>, stops: List<LatLon>, trackedIndex: Int): ClippedRoute<LatLon>? =
    clipRouteToTrackedStop(polyline, stops, trackedIndex) { it }

/** The next arrival's tripId per direction group (max three). Arrivals without a tripId are skipped. */
internal fun trackedTripIds(arrivals: List<Arrival>): List<String> {
    val seenDirections = HashSet<String>()
    val out = mutableListOf<String>()
    for (a in arrivals) {
        if (a.tripId.isEmpty() || a.headSign in seenDirections) continue
        seenDirections += a.headSign
        out += a.tripId
        if (out.size == 3) break
    }
    return out
}

/** Live vehicles whose tripId is one of the tracked trips. */
internal fun matchingVehicles(vehicles: List<FutarVehicle>, tripIds: List<String>): List<FutarVehicle> {
    val set = tripIds.toSet()
    return vehicles.filter { !it.tripId.isNullOrEmpty() && it.tripId in set }
}

/**
 * Index of the tracked stop in an ordered stop-id list: exact platform id first, then the
 * station-level id via `parentStationId`. -1 when not found.
 */
internal fun resolveTrackedStopIndex(stopIds: List<String>, stopRefs: Map<String, FutarStop>, trackedStopId: String?): Int {
    if (trackedStopId.isNullOrEmpty() || stopIds.isEmpty()) return -1
    val exact = stopIds.indexOf(trackedStopId)
    if (exact >= 0) return exact
    return stopIds.indexOfFirst { stopRefs[it]?.parentStationId == trackedStopId }
}

internal data class RouteProgress(
    /** Index of the next stop the vehicle has NOT yet reached (-1 when none). */
    val vehicleIndex: Int,
    /** Stops remaining to the tracked stop (incl. the next); null when no vehicle. */
    val stopsLeft: Int?,
    /** Vehicle position along the route, 0..1; null when no vehicle or geometry. */
    val vehicleFraction: Double?,
    /** Tracked stop position along the route, 0..1 (index-based without geometry). */
    val trackedFraction: Double,
    /** Real-time fractional position within the current stop-to-stop segment, 0..1. */
    val segmentFraction: Double?,
)

/** Progress of a vehicle (its `location`) along ONE direction's ordered stops + polyline. */
internal fun routeProgress(
    stops: List<LatLon>,
    polyline: List<LatLon>,
    vehicleLocation: LatLon?,
    trackedIndex: Int,
): RouteProgress {
    val total = stops.size
    fun clamp(x: Double) = max(0.0, min(1.0, x))
    val trackedFraction = if (total == 0) 0.5
    else if (trackedIndex >= 0) clamp(trackedIndex.toDouble() / max(1, total - 1)) else 0.5
    if (vehicleLocation == null || total == 0 || polyline.size < 2) {
        return RouteProgress(-1, null, null, trackedFraction, null)
    }
    val projector = makeProjector(polyline, 400.0, 220.0)
    val pathPts = polyline.map(projector)
    val stopDists = stops.map { s -> projectVehicleOntoPolyline(projector(LatLon(s.lat, s.lon)), pathPts)?.distance ?: 0.0 }
    val totalDist = stopDists[stopDists.size - 1].let { if (it == 0.0 || it.isNaN()) 1.0 else it }
    val proj = projectVehicleOntoPolyline(projector(vehicleLocation), pathPts)
        ?: return RouteProgress(-1, null, null, trackedFraction, null)
    val vehicleIndex = nextStopIndex(stopDists, proj.distance)
    val stopsLeft = if (trackedIndex >= 0 && trackedIndex >= vehicleIndex) trackedIndex - vehicleIndex + 1 else null

    val prevDist = if (vehicleIndex > 0) stopDists[vehicleIndex - 1] else 0.0
    val nextDist = stopDists.getOrNull(vehicleIndex) ?: (prevDist + 1)
    val segSpan = max(1.0, nextDist - prevDist)
    val segmentFraction = clamp((proj.distance - prevDist) / segSpan)

    return RouteProgress(
        vehicleIndex = vehicleIndex,
        stopsLeft = stopsLeft,
        vehicleFraction = clamp(proj.distance / totalDist),
        trackedFraction = clamp(stopDists[if (trackedIndex >= 0) trackedIndex else 0] / totalDist),
        segmentFraction = segmentFraction,
    )
}

internal data class ApproximateVehicleInput(
    /** Only departureTime / arrivalTime (epoch s) and shapeDistTraveled are read. */
    val stopTimes: List<FutarTripStopTime>,
    val polyline: List<LatLon>,
    val nowMs: Long,
    /** Predicted − scheduled at the tracked stop; metros have none, so 0. */
    val delayMs: Long = 0,
)

/**
 * Where the vehicle on a trip SHOULD be right now per the timetable (delay-adjusted),
 * interpolated along the trip's shape distance. Null when the data can't support it.
 */
internal fun approximateVehiclePosition(input: ApproximateVehicleInput): LatLon? {
    class Timed(val departureTime: Double, val shapeDistTraveled: Double)
    val timed = input.stopTimes.mapNotNull { s ->
        val t = s.departureTime ?: s.arrivalTime
        val d = s.shapeDistTraveled
        if (t != null && d != null) Timed(t.toDouble(), d) else null
    }
    val polyline = input.polyline
    if (timed.size < 2 || polyline.size < 2) return null

    val nowSec = input.nowMs / 1000.0 - input.delayMs / 1000.0
    val first = timed[0]
    val last = timed[timed.size - 1]
    if (nowSec <= first.departureTime) return pointAtFraction(polyline, 0.0)
    if (nowSec >= last.departureTime) return pointAtFraction(polyline, 1.0)

    var i = 0
    while (i < timed.size - 2 && timed[i + 1].departureTime <= nowSec) i++
    val a = timed[i]
    val b = timed[i + 1]
    val span = b.departureTime - a.departureTime
    val t = if (span <= 0) 0.0 else min(1.0, max(0.0, (nowSec - a.departureTime) / span))
    val dist = a.shapeDistTraveled + t * (b.shapeDistTraveled - a.shapeDistTraveled)
    val total = last.shapeDistTraveled - first.shapeDistTraveled
    val fraction = if (total <= 0) 0.0 else min(1.0, max(0.0, (dist - first.shapeDistTraveled) / total))
    return pointAtFraction(polyline, fraction)
}
