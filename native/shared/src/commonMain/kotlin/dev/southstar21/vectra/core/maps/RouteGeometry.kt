package dev.southstar21.vectra.core.maps

import dev.southstar21.vectra.core.util.roundHalfUp
import dev.southstar21.vectra.core.util.toFixed
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/* Route polyline geometry. */

/**
 * Camera-fit key for a whole set of drawn geometry. Covers EVERY polyline's
 * endpoints + length AND every walk's endpoints. Returns "" when there is nothing to fit.
 */
fun journeyFitKey(polylines: List<List<LatLon>>, walks: List<List<LatLon>> = emptyList()): String {
    val parts = mutableListOf<String>()
    for (pl in polylines) {
        if (pl.size < 2) continue
        val a = pl[0]
        val b = pl[pl.size - 1]
        parts += "${toFixed(a.lat, 5)},${toFixed(a.lon, 5)}:${toFixed(b.lat, 5)},${toFixed(b.lon, 5)}:${pl.size}"
    }
    for (w in walks) {
        if (w.size < 2) continue
        parts += "w${toFixed(w[0].lat, 5)},${toFixed(w[0].lon, 5)}-${toFixed(w[1].lat, 5)},${toFixed(w[1].lon, 5)}"
    }
    return parts.joinToString("|")
}

/** A point in a 2D (projected / SVG) coordinate space. */
data class Point(val x: Double, val y: Double)

/** Decode a Google-encoded polyline into lat/lon pairs (1e-5 precision). */
fun decodePolyline(encoded: String): List<LatLon> {
    val points = mutableListOf<LatLon>()
    var index = 0
    var lat = 0
    var lon = 0
    // Reading past the end yields 0, which ends the varint loop.
    fun charAt(i: Int): Int = if (i < encoded.length) encoded[i].code - 63 else 0
    while (index < encoded.length) {
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = charAt(index++)
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
        result = 0
        shift = 0
        do {
            b = charAt(index++)
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
        points += LatLon(roundHalfUp(lat * 1e-5 * 1e5) / 1e5, roundHalfUp(lon * 1e-5 * 1e5) / 1e5)
    }
    return points
}

/**
 * Build a projector mapping lat/lon into a `width` × `height` viewBox.
 * Equirectangular (longitude scaled by cos of the mid latitude, so distances and angles hold),
 * uniform scale, 8% padding, north up.
 */
fun makeProjector(latLons: List<LatLon>, width: Double, height: Double): (LatLon) -> Point {
    val pad = 0.08
    if (latLons.isEmpty()) return { Point(width / 2, height / 2) }
    var minLon = Double.POSITIVE_INFINITY
    var maxLon = Double.NEGATIVE_INFINITY
    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    for (p in latLons) {
        if (p.lon < minLon) minLon = p.lon
        if (p.lon > maxLon) maxLon = p.lon
        if (p.lat < minLat) minLat = p.lat
        if (p.lat > maxLat) maxLat = p.lat
    }
    val kx = cos(rad((minLat + maxLat) / 2))
    val spanLon = (maxLon - minLon) * kx
    val spanLat = maxLat - minLat
    val usableW = width * (1 - 2 * pad)
    val usableH = height * (1 - 2 * pad)
    val scale = if (spanLon == 0.0 && spanLat == 0.0) 1.0 else min(
        if (spanLon == 0.0) Double.POSITIVE_INFINITY else usableW / spanLon,
        if (spanLat == 0.0) Double.POSITIVE_INFINITY else usableH / spanLat,
    )
    val cx = (minLon + maxLon) / 2
    val cy = (minLat + maxLat) / 2
    return { p -> Point(width / 2 + (p.lon - cx) * kx * scale, height / 2 - (p.lat - cy) * scale) }
}

/** Result of [projectVehicleOntoPolyline]. */
data class PolylineProjection(val point: Point, val distance: Double)

/** Project `p` onto the polyline: closest point on the nearest segment + cumulative distance. */
fun projectVehicleOntoPolyline(p: Point, polyline: List<Point>): PolylineProjection? {
    if (polyline.isEmpty()) return null
    if (polyline.size == 1) return PolylineProjection(polyline[0], 0.0)

    var bestPoint: Point? = null
    var bestDistSq = 0.0
    var bestSeg = 0
    for (i in 0 until polyline.size - 1) {
        val a = polyline[i]
        val b = polyline[i + 1]
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        var t = if (lenSq == 0.0) 0.0 else ((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq
        t = max(0.0, min(1.0, t))
        val px = a.x + t * abx
        val py = a.y + t * aby
        val distSq = (p.x - px).pow(2) + (p.y - py).pow(2)
        if (bestPoint == null || distSq < bestDistSq) {
            bestPoint = Point(px, py)
            bestDistSq = distSq
            bestSeg = i
        }
    }
    val point = bestPoint ?: return null
    var distance = 0.0
    for (i in 0 until bestSeg) {
        val a = polyline[i]
        val b = polyline[i + 1]
        distance += hypot(b.x - a.x, b.y - a.y)
    }
    val a = polyline[bestSeg]
    distance += hypot(point.x - a.x, point.y - a.y)
    return PolylineProjection(point, distance)
}

/** Cumulative polyline length at each vertex (first is 0). */
fun cumulativeDistances(points: List<Point>): List<Double> {
    val out = mutableListOf(0.0)
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        out += out[i - 1] + hypot(b.x - a.x, b.y - a.y)
    }
    return out
}

/** Index of the first stop the vehicle has NOT yet reached; clamps to the last. */
fun nextStopIndex(stopDistances: List<Double>, vehicleDistance: Double): Int {
    val i = stopDistances.indexOfFirst { it >= vehicleDistance }
    return if (i == -1) stopDistances.size - 1 else i
}

private fun rad(d: Double) = d * PI / 180

/** Haversine distance in meters between two lat/lon points. */
fun haversineMeters(a: LatLon, b: LatLon): Double {
    val r = 6_371_000.0
    val dLat = rad(b.lat - a.lat)
    val dLon = rad(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

/** The point at `fraction` (0..1, clamped) of the polyline's geographic length. Null when empty. */
fun pointAtFraction(polyline: List<LatLon>, fraction: Double): LatLon? {
    if (polyline.isEmpty()) return null
    val f = max(0.0, min(1.0, fraction))
    if (polyline.size == 1 || f <= 0) return polyline[0]
    if (f >= 1) return polyline[polyline.size - 1]
    val dists = cumulativeHaversineDistances(polyline)
    val target = f * dists[dists.size - 1]
    for (i in 0 until polyline.size - 1) {
        if (target <= dists[i + 1] || i == polyline.size - 2) {
            val seg = dists[i + 1] - dists[i]
            val t = if (seg <= 0) 0.0 else (target - dists[i]) / seg
            return LatLon(
                polyline[i].lat + (polyline[i + 1].lat - polyline[i].lat) * t,
                polyline[i].lon + (polyline[i + 1].lon - polyline[i].lon) * t,
            )
        }
    }
    return polyline[polyline.size - 1]
}

/** Cumulative haversine length in meters at each vertex (first is 0). */
fun cumulativeHaversineDistances(polyline: List<LatLon>): List<Double> {
    val out = mutableListOf(0.0)
    for (i in 1 until polyline.size) out += out[i - 1] + haversineMeters(polyline[i - 1], polyline[i])
    return out
}

/** [x], or 1 when it is 0 or NaN. */
private fun orOne(x: Double): Double = if (x == 0.0 || x.isNaN()) 1.0 else x

/** The point at cumulative distance `d` meters along the polyline (clamped to its ends). */
fun pointAtDistance(polyline: List<LatLon>, dists: List<Double>, d: Double): LatLon {
    if (polyline.isEmpty()) return LatLon(0.0, 0.0)
    if (polyline.size == 1) return polyline[0]
    val total = orOne(dists[dists.size - 1])
    val target = max(0.0, min(total, d))
    if (target <= 0) return polyline[0]
    if (target >= total) return polyline[polyline.size - 1]
    // Binary search: this runs per vehicle per animation frame on long polylines.
    var lo = 0
    var hi = polyline.size - 2
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (target <= dists[mid + 1]) hi = mid else lo = mid + 1
    }
    val segLen = dists[lo + 1] - dists[lo]
    val t = if (segLen <= 0) 0.0 else (target - dists[lo]) / segLen
    return LatLon(
        polyline[lo].lat + (polyline[lo + 1].lat - polyline[lo].lat) * t,
        polyline[lo].lon + (polyline[lo + 1].lon - polyline[lo].lon) * t,
    )
}

/**
 * The polyline prefix from its start up to cumulative distance `d` meters, with the
 * cut point interpolated.
 */
fun cutPolylineAtDistance(polyline: List<LatLon>, dists: List<Double>, d: Double): List<LatLon> {
    if (polyline.isEmpty()) return emptyList()
    val total = orOne(dists[dists.size - 1])
    val target = max(0.0, min(total, d))
    val out = mutableListOf(LatLon(polyline[0].lat, polyline[0].lon))
    for (i in 0 until polyline.size - 1) {
        if (target <= dists[i + 1] || i == polyline.size - 2) {
            val segLen = dists[i + 1] - dists[i]
            val t = if (segLen <= 0) 0.0 else (target - dists[i]) / segLen
            out += LatLon(
                polyline[i].lat + (polyline[i + 1].lat - polyline[i].lat) * t,
                polyline[i].lon + (polyline[i + 1].lon - polyline[i].lon) * t,
            )
            return out
        }
        out += LatLon(polyline[i + 1].lat, polyline[i + 1].lon)
    }
    return out
}

/** Difference between two headings in degrees (0..180). */
fun angleDifference(a: Double, b: Double): Double {
    val diff = abs(a - b) % 360
    return if (diff > 180) 360 - diff else diff
}

/** Result of [snapToPolyline]. */
data class SnappedPoint(val point: LatLon, val distance: Double)

private class SegHit(val seg: Int, val t: Double, val distSq: Double)

/**
 * Snap `p` onto the polyline: nearest point on the closest segment plus its cumulative
 * distance (meters). With `preferredBearing`, segments heading the same way are favoured.
 */
fun snapToPolyline(p: LatLon, polyline: List<LatLon>, preferredBearing: Double? = null): SnappedPoint? {
    if (polyline.isEmpty()) return null
    if (polyline.size == 1) return SnappedPoint(polyline[0], 0.0)

    // A degree of longitude is cos(lat) as long as one of latitude (0.68 at Budapest); unscaled,
    // east-west offsets would count ~1.48× and skew the perpendicular foot and the segment choice.
    val kx = cos(rad(p.lat))
    var best: SegHit? = null
    var bestMatchingBearing: SegHit? = null
    for (i in 0 until polyline.size - 1) {
        val a = polyline[i]
        val b = polyline[i + 1]
        val dLat = b.lat - a.lat
        val dLon = (b.lon - a.lon) * kx
        val lenSq = dLat * dLat + dLon * dLon
        val pLon = (p.lon - a.lon) * kx
        var t = if (lenSq == 0.0) 0.0 else ((p.lat - a.lat) * dLat + pLon * dLon) / lenSq
        t = max(0.0, min(1.0, t))
        val distSq = (p.lat - (a.lat + t * dLat)).pow(2) + (pLon - t * dLon).pow(2)
        if (best == null || distSq < best.distSq) best = SegHit(i, t, distSq)

        if (preferredBearing != null && preferredBearing.isFinite() && preferredBearing >= 0 && lenSq > 1e-12) {
            val segBearing = bearingDegrees(a, b)
            if (angleDifference(segBearing, preferredBearing) <= 90) {
                if (bestMatchingBearing == null || distSq < bestMatchingBearing.distSq) {
                    bestMatchingBearing = SegHit(i, t, distSq)
                }
            }
        }
    }
    val chosen = if (bestMatchingBearing != null && best != null &&
        bestMatchingBearing.distSq <= max(best.distSq * 4, 1e-6)
    ) bestMatchingBearing else best
    chosen ?: return null

    val a = polyline[chosen.seg]
    val b = polyline[chosen.seg + 1]
    val point = LatLon(a.lat + chosen.t * (b.lat - a.lat), a.lon + chosen.t * (b.lon - a.lon))
    var distance = 0.0
    for (i in 0 until chosen.seg) distance += haversineMeters(polyline[i], polyline[i + 1])
    distance += haversineMeters(a, point)
    return SnappedPoint(point, distance)
}

/** A vehicle snapped onto a route polyline, facing along the direction of travel. */
data class PlacedVehicle(
    val id: String,
    val lat: Double,
    val lon: Double,
    /** Bearing in degrees: 0 = north, clockwise. */
    val bearing: Double,
)

/** Input for [placeVehiclesOnPolyline]. */
data class VehiclePlacementInput(val id: String, val lat: Double, val lon: Double, val bearing: Double? = null)

/** Initial bearing from `from` to `to` in degrees (0 = north, clockwise). */
fun bearingDegrees(from: LatLon, to: LatLon): Double {
    val y = sin(rad(to.lon - from.lon)) * cos(rad(to.lat))
    val x = cos(rad(from.lat)) * sin(rad(to.lat)) -
        sin(rad(from.lat)) * cos(rad(to.lat)) * cos(rad(to.lon - from.lon))
    return (atan2(y, x) * 180 / PI + 360) % 360
}

/** Forward tangent bearing (degrees) along a polyline at a cumulative distance. */
fun bearingAlongPolyline(
    polyline: List<LatLon>,
    dists: List<Double>,
    distance: Double,
    lookAheadMeters: Double = 20.0,
): Double {
    if (polyline.size < 2 || dists.size < 2) return 0.0
    val total = orOne(dists[dists.size - 1])
    val current = max(0.0, min(total, distance))
    val ahead = min(total, current + lookAheadMeters)
    val behind = max(0.0, current - lookAheadMeters)
    val forward = ahead - current >= 0.5
    val currentPt = pointAtDistance(polyline, dists, current)
    val targetPt = pointAtDistance(polyline, dists, if (forward) ahead else behind)
    return if (forward) bearingDegrees(currentPt, targetPt) else bearingDegrees(targetPt, currentPt)
}

/**
 * Snap each vehicle onto the polyline and point it along the direction of travel.
 * A telemetry bearing within 45° of the line is replaced by the line's; otherwise kept.
 */
fun placeVehiclesOnPolyline(
    vehicles: List<VehiclePlacementInput>,
    polyline: List<LatLon>,
    lookAheadMeters: Double = 30.0,
): List<PlacedVehicle> {
    if (polyline.size < 2) return vehicles.map { PlacedVehicle(it.id, it.lat, it.lon, it.bearing ?: 0.0) }
    val dists = cumulativeHaversineDistances(polyline)
    return vehicles.map { v ->
        val snapped = snapToPolyline(LatLon(v.lat, v.lon), polyline, v.bearing)
            ?: return@map PlacedVehicle(v.id, v.lat, v.lon, v.bearing ?: 0.0)
        val polyBearing = bearingAlongPolyline(polyline, dists, snapped.distance, lookAheadMeters)
        val tb = v.bearing
        val hasTelemetry = tb != null && tb.isFinite() && tb >= 0
        val bearing = if (hasTelemetry) {
            if (angleDifference(polyBearing, tb) <= 45) polyBearing else tb
        } else polyBearing
        PlacedVehicle(v.id, snapped.point.lat, snapped.point.lon, bearing)
    }
}
