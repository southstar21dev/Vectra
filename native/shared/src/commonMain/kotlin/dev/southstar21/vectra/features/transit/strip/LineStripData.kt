package dev.southstar21.vectra.features.transit.strip

import dev.southstar21.vectra.features.transit.DirectionView
import dev.southstar21.vectra.features.transit.extractHeadsignDestination
import dev.southstar21.vectra.features.transit.isReplacementBus
import dev.southstar21.vectra.features.transit.live.RouteProgress


/** Stops between the vehicle behind [p] and your stop (fractional); null when it can't be placed. */
private fun DirectionView.stopsUntil(p: RouteProgress): Double? =
    if (p.vehicleIndex < 0 || trackedIndex < 0 || p.stopsLeft == null) null
    else (trackedIndex - p.vehicleIndex) + (1.0 - (p.segmentFraction ?: 0.0))

/** How far (in stops) a direction's vehicle still is from your stop; null without a position. */
internal fun DirectionView.vehicleDistance(): Double? = stopsUntil(progress)

/** Where this direction goes: the arrival's headsign without its "via" part, else the last stop. */
internal fun DirectionView.destination(): String =
    extractHeadsignDestination(arrival?.headSign?.takeIf { it.isNotEmpty() } ?: stops.lastOrNull()?.name)

/** This direction as the strip needs it. */
internal fun DirectionView.toStripDirection(): StripDirection {
    val next = upcoming.firstOrNull()
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
        approach = stops.getOrNull(trackedIndex - 1)?.name,
        origin = origin,
        terminus = stops.size > 1 && trackedIndex == stops.lastIndex,
        replacement = arrival?.isReplacementBus == true,
        later = upcoming,
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

