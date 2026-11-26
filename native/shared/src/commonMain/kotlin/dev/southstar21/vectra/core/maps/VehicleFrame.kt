package dev.southstar21.vectra.core.maps

import androidx.compose.runtime.Immutable

/** One vehicle's rendered state for the current frame. */
@Immutable
data class VehicleFrame(
    val id: String,
    /** Where the marker sits now (snapped onto / gliding along the polyline). */
    val position: LatLon,
    /** Heading in degrees (0 = north), rounded to a whole degree. */
    val bearing: Double,
    val variant: VehicleVariant,
)
