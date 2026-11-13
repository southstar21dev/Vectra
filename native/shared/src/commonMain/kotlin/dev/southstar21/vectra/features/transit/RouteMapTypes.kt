package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.LatLon

data class RouteMapStop(
    val name: String,
    val lat: Double,
    val lon: Double,
    val isTracked: Boolean,
    val isSelected: Boolean? = null,
)

data class RouteMapVehicle(
    val vehicleId: String,
    val location: LatLon,
    val isApproximate: Boolean? = null,
    val bearing: Double? = null,
    /** Its countdown to your stop ("1'", "NOW"), shown above it on the map. */
    val label: String? = null,
    /** Due now or within a minute: the label turns green. */
    val labelSoon: Boolean = false,
)

data class RouteMapSecondaryLine(
    val stops: List<RouteMapStop>,
    val polyline: List<LatLon>,
    val vehicles: List<RouteMapVehicle>,
)

/** A stop shown on the route preview map. */
data class RoutePreviewStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** Stops the user has already selected/tracked — highlighted. */
    val selected: Boolean? = null,
)

/** A live vehicle shown on the route preview map. */
data class RoutePreviewVehicle(
    val id: String,
    val lat: Double,
    val lon: Double,
    val bearing: Double? = null,
)
