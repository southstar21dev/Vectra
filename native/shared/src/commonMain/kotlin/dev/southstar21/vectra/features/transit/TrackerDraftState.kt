package dev.southstar21.vectra.features.transit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.southstar21.vectra.core.maps.LatLon

/** The stops being picked for a tracker and the route they sit on, for the add and edit screens. */
@Stable
internal class TrackerDraftState(private val base: State<List<TrackerStop>>) {
    // Null until the first edit, so the draft follows [base] until then.
    private var edited by mutableStateOf<List<TrackerStop>?>(null)

    var stops: List<TrackerStop>
        get() = edited ?: base.value
        set(value) {
            edited = value
        }

    /** Null until the route's details have loaded. */
    var routeStops by mutableStateOf<List<FutarStop>?>(null)
    var routeHeadsigns by mutableStateOf<List<String>>(emptyList())
    var routePolylines by mutableStateOf<List<List<LatLon>>>(emptyList())

    fun showRoute(details: RouteDetailsParsed) {
        routeHeadsigns = details.headsigns
        routePolylines = details.polylines
        routeStops = details.stops
    }

    fun clearRoute() {
        routeStops = null
        routeHeadsigns = emptyList()
        routePolylines = emptyList()
    }

    fun has(stopId: String): Boolean = stops.any { it.stopId == stopId }

    /** False when the tracker is already at [MaxTrackerStops]. */
    fun add(stop: FutarStop): Boolean {
        if (stops.size >= MaxTrackerStops) return false
        stops = stops + TrackerStop(stop.id, stop.name, "")
        return true
    }

    fun remove(stopId: String) {
        stops = stops.filter { it.stopId != stopId }
    }

    fun setDirection(stopId: String, headsign: String) {
        stops = stops.map { if (it.stopId == stopId) it.copy(headsign = headsign) else it }
    }
}

@Composable
internal fun rememberTrackerDraft(base: List<TrackerStop> = emptyList()): TrackerDraftState {
    val latest = rememberUpdatedState(base)
    return remember { TrackerDraftState(latest) }
}

/**
 * The vehicle worth showing on the route preview: the earliest departure at [stops] names the
 * one the user will take; every other vehicle on the line is noise, so with no stops picked
 * nothing shows. Polls while [routeId] is set.
 */
@Composable
internal fun rememberPreviewVehicles(repo: TransitRepository, routeId: String?, stops: List<TrackerStop>): List<RoutePreviewVehicle> {
    val vehiclesQuery = repo.vehicles.collect(routeId)
    val stopIds = remember(stops) { stops.map { it.stopId } }
    val arrivals = rememberMultiStopArrivals(repo, stopIds)
    val relevantTripIds = remember(stops, arrivals, routeId) { relevantTripIdsFor(routeId.orEmpty(), stops, arrivals) }
    return remember(vehiclesQuery.data, relevantTripIds) { previewVehiclesFrom(vehiclesQuery.data, relevantTripIds) }
}
