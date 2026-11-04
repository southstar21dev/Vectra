package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.VehicleVariant
import dev.southstar21.vectra.core.maps.angleDifference
import dev.southstar21.vectra.core.maps.bearingAlongPolyline
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/* Pure vehicle-animation maths; the per-frame driver lives in VehicleAnimations.kt. */

const val VehicleAnimMaxMs = 10_000.0
const val VehicleAnimMinMs = 600.0
const val VehicleAnimSpeedMps = 10.0

data class AnimatedVehicleInput(
    val id: String,
    val polyline: List<LatLon>,
    val location: LatLon,
    val variant: VehicleVariant? = null,
    val bearing: Double? = null,
)

fun easeInOutQuad(t: Double): Double = if (t < 0.5) 2 * t * t else 1 - (-2 * t + 2).pow(2) / 2

/** The animation segment fields of a vehicle's state (`fromDist..toDist` over `durationMs`). */
data class VehicleTransition(
    val fromDist: Double,
    val toDist: Double,
    val startMs: Double,
    val durationMs: Double,
    val done: Boolean,
)

/** Next transition from the previous `currentDist` (null = first sighting → snap). */
fun transitionFor(previousCurrentDist: Double?, targetDist: Double, now: Double): VehicleTransition {
    if (previousCurrentDist == null) return VehicleTransition(targetDist, targetDist, now, 0.0, true)
    val delta = abs(targetDist - previousCurrentDist)
    if (delta < 1) return VehicleTransition(targetDist, targetDist, now, 0.0, true)
    return VehicleTransition(
        fromDist = previousCurrentDist,
        toDist = targetDist,
        startMs = now,
        durationMs = min(VehicleAnimMaxMs, max(VehicleAnimMinMs, delta / VehicleAnimSpeedMps)),
        done = false,
    )
}

/**
 * Heading for a vehicle at `dist` along the polyline: the line's tangent, unless a valid
 * telemetry `fallbackBearing` disagrees by more than 45° (then telemetry wins).
 */
fun resolveBearing(polyline: List<LatLon>, dists: List<Double>, dist: Double, fallbackBearing: Double? = null): Double {
    val hasFallback = fallbackBearing != null && fallbackBearing.isFinite() && fallbackBearing >= 0
    if (polyline.size >= 2 && dists.size >= 2) {
        val polyBearing = bearingAlongPolyline(polyline, dists, dist)
        if (!hasFallback) return polyBearing
        return if (angleDifference(polyBearing, fallbackBearing) <= 45) polyBearing else fallbackBearing
    }
    return if (hasFallback) fallbackBearing else 0.0
}
