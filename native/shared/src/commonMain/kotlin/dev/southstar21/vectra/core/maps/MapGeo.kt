package dev.southstar21.vectra.core.maps

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * GeoJSON is returned as kotlinx JsonObject (Feature / FeatureCollection) whose
 * property names the map layers read; feed `.toString()` to
 * `GeoJsonData.JsonString`.
 */

@Serializable
data class LatLon(val lat: Double = 0.0, val lon: Double = 0.0)

private val EmptyProps = JsonObject(emptyMap())

fun isValidLatLon(p: LatLon?): Boolean =
    p != null && p.lat.isFinite() && p.lon.isFinite() && (p.lat != 0.0 || p.lon != 0.0)

/** Overload for stop-like values. */
fun isValidLatLon(lat: Double, lon: Double): Boolean = isValidLatLon(LatLon(lat, lon))

/** True when both polylines contain exactly the same points in the same order. */
fun samePoints(a: List<LatLon>, b: List<LatLon>): Boolean {
    if (a === b) return true
    if (a.size != b.size) return false
    for (i in a.indices) {
        if (a[i].lat != b[i].lat || a[i].lon != b[i].lon) return false
    }
    return true
}

private fun lonLat(lon: Double, lat: Double) = JsonArray(listOf(JsonPrimitive(lon), JsonPrimitive(lat)))

private fun lineStringFeature(points: List<LatLon>, properties: JsonObject): JsonObject = buildJsonObject {
    put("type", "Feature")
    put("properties", properties)
    put("geometry", buildJsonObject {
        put("type", "LineString")
        put("coordinates", JsonArray(points.map { lonLat(it.lon, it.lat) }))
    })
}

private fun featureCollection(features: List<JsonObject>): JsonObject = buildJsonObject {
    put("type", "FeatureCollection")
    put("features", JsonArray(features))
}

/** FeatureCollection of LineStrings; polylines with <2 points are dropped (indices stay original). */
fun lineFeatureCollection(
    polylines: List<List<LatLon>>,
    propertiesFor: ((index: Int) -> JsonObject?)? = null,
): JsonObject = featureCollection(
    polylines.withIndex()
        .filter { it.value.size >= 2 }
        .map { (index, pts) -> lineStringFeature(pts, propertiesFor?.invoke(index) ?: EmptyProps) },
)

/** FeatureCollection of stop Points with `id`, `name`, `kind`, `labelPriority` properties. */
fun stopFeatureCollection(stops: List<MapStop>): JsonObject = featureCollection(
    stops.filter { isValidLatLon(it.lat, it.lon) }.map { s ->
        buildJsonObject {
            put("type", "Feature")
            put("properties", buildJsonObject {
                put("id", s.id ?: "")
                put("name", s.name)
                put("kind", (s.kind ?: MapStopKind.Regular).value)
                put("labelPriority", labelPriority(s.kind))
            })
            put("geometry", buildJsonObject {
                put("type", "Point")
                put("coordinates", lonLat(s.lon, s.lat))
            })
        }
    },
)

/** A 64-segment closed Polygon Feature approximating a GPS accuracy circle; null when invalid. */
fun accuracyCircleFeature(center: LatLon, radiusMeters: Double): JsonObject? {
    if (!isValidLatLon(center) || !(radiusMeters > 0)) return null
    val points = 64
    val km = radiusMeters / 1000
    val latRad = center.lat * PI / 180
    val coords = (0..points).map { i ->
        val angle = i.toDouble() / points * 2 * PI
        val dLat = km / 111.32 * cos(angle)
        val dLon = km / (111.32 * cos(latRad)) * sin(angle)
        lonLat(center.lon + dLon, center.lat + dLat)
    }
    return buildJsonObject {
        put("type", "Feature")
        put("properties", EmptyProps)
        put("geometry", buildJsonObject {
            put("type", "Polygon")
            put("coordinates", JsonArray(listOf(JsonArray(coords))))
        })
    }
}

/** The traveled prefix of `polyline` up to `distance` meters as a LineString Feature; null when <2 points. */
fun cutLineFeature(
    polyline: List<LatLon>,
    dists: List<Double>,
    distance: Double,
    properties: JsonObject = EmptyProps,
): JsonObject? {
    if (!(distance > 0)) return null
    val pts = cutPolylineAtDistance(polyline, dists, distance)
    if (pts.size < 2) return null
    return lineStringFeature(pts, properties)
}

data class Bounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

fun boundsOf(points: List<LatLon>): Bounds? {
    if (points.isEmpty()) return null
    var minLat = points[0].lat
    var maxLat = points[0].lat
    var minLon = points[0].lon
    var maxLon = points[0].lon
    for (p in points) {
        if (p.lat < minLat) minLat = p.lat
        if (p.lat > maxLat) maxLat = p.lat
        if (p.lon < minLon) minLon = p.lon
        if (p.lon > maxLon) maxLon = p.lon
    }
    return Bounds(minLat, minLon, maxLat, maxLon)
}
