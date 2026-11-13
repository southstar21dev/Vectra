package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.isValidLatLon

/** Stops between the vehicle behind [p] and your stop (fractional); null when it can't be placed. */
private fun DirectionView.stopsUntil(p: RouteProgress): Double? =
    if (p.vehicleIndex < 0 || trackedIndex < 0 || p.stopsLeft == null) null
    else (trackedIndex - p.vehicleIndex) + (1.0 - (p.segmentFraction ?: 0.0))

/** How far (in stops) a direction's vehicle still is from your stop; null without a position. */
fun DirectionView.vehicleDistance(): Double? = stopsUntil(progress)

/** Stops between a vehicle at [location] and your stop along this direction; null when it can't be placed. */
fun DirectionView.distanceOf(location: LatLon?): Double? {
    if (!isValidLatLon(location) || trackedIndex < 0) return null
    return stopsUntil(routeProgress(stops.map { LatLon(it.lat, it.lon) }, polyline, location, trackedIndex))
}

/** Where this direction goes: the arrival's headsign without its "via" part, else the last stop. */
fun DirectionView.destination(): String =
    extractHeadsignDestination(arrival?.headSign?.takeIf { it.isNotEmpty() } ?: stops.lastOrNull()?.name)

/**
 * This direction as the strip needs it. [routeVehicles]: every live vehicle on the route, to
 * place the one after the next. [leftAt]: when its last vehicle left your stop.
 */
fun DirectionView.toStripDirection(routeVehicles: List<FutarVehicle> = emptyList(), leftAt: Long? = null): StripDirection {
    val next = upcoming.firstOrNull()
    val nextLocation = next?.tripId?.takeIf { it.isNotEmpty() }?.let { trip -> routeVehicles.firstOrNull { it.tripId == trip }?.location }
    val origin = trackedIndex == 0 && stops.size > 1
    return StripDirection(
        destination = destination(),
        arrival = arrival,
        next = next,
        // At a first stop the waiting vehicle reads as just past it; any live vehicle there is waiting.
        // The metro has no live vehicles, only its timetable estimate (approxVehicle, already in progress).
        vehicleDistance = when {
            vehicles.isEmpty() && approxVehicle == null -> null
            // Only a reporting vehicle waits: a timetable estimate parks at the start before every departure.
            origin -> if (vehicles.isNotEmpty()) 0.0 else null
            else -> vehicleDistance()
        },
        nextDistance = distanceOf(nextLocation),
        leftAt = leftAt,
        approach = stops.getOrNull(trackedIndex - 1)?.name,
        origin = origin,
        terminus = stops.size > 1 && trackedIndex == stops.lastIndex,
        replacement = arrival?.isReplacementBus == true,
    )
}

/**
 * Where a time without a position sits: at the edge its vehicle comes in from, or at your stop
 * once it is due (the feeds update apart, so a due trip can briefly lack a position).
 */
internal fun unplacedAt(heading: Int, minutes: Int): Float = when {
    minutes <= 0 -> StripSideStops.toFloat()
    heading > 0 -> 0f
    else -> (StripStopCount - 1).toFloat()
}

/** A direction's lead trip as last seen: [dueMs] at your stop, in arrivals fetched at [fetchedAt]. */
internal class SeenLead(val tripId: String, val dueMs: Long, val fetchedAt: Long)

/** Arrivals fetched longer ago than this are old news: a lead change against them isn't a departure seen now. */
private const val FreshArrivalsMs = 90_000L

/**
 * Whether a direction's lead trip changing from [prev] to [nextTrip] means its vehicle just left
 * your stop: [prev] came from freshly fetched arrivals and was due within a minute either side of
 * [now]. Cached arrivals being replaced on opening (or after a while in the background) only look
 * like a departure: the lead they had left long ago, or is still on its way.
 */
internal fun justDeparted(prev: SeenLead, nextTrip: String, now: Long): Boolean {
    if (prev.tripId == nextTrip || now - prev.fetchedAt > FreshArrivalsMs) return false
    return prev.dueMs in (now - JustLeftMs)..(now + JustLeftMs)
}
