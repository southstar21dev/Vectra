package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.LatLon
import kotlin.math.ceil

/**
 * One direction of the tracked route — stops, geometry, vehicles and progress.
 * An all-directions tracker yields up to three of these; a direction tracker one.
 */
data class DirectionView(
    val tripId: String?,
    val stops: List<RouteMapStop>,
    val polyline: List<LatLon>,
    val vehicles: List<RouteMapVehicle>,
    val arrival: Arrival?,
    /** The next departures after `arrival`, same direction. */
    val upcoming: List<Arrival>,
    val trackedIndex: Int,
    val progress: RouteProgress,
    /** Raw timed stopTimes from the trip entry — approximate-position input. */
    val stopTimes: List<FutarTripStopTime>,
    /** Schedule-interpolated metro position when no live vehicle reports. */
    val approxVehicle: RouteMapVehicle? = null,
)

/** Whole minutes until [ms], rounded up; 0 once it is due. */
internal fun etaMinutes(ms: Long, now: Long): Int = maxOf(0, ceil((ms - now) / 60_000.0).toInt())
