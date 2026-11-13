package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.LatLon
import kotlin.math.roundToLong

/** Stops before yours on a demo trip: enough to place a far-away vehicle. */
private const val DemoStopsBefore = 10
private const val DemoStopsAfter = 4
private const val DemoLonStep = 0.004
private const val DemoBaseLat = 47.49
private const val DemoBaseLon = 19.0

/** A demo direction's trip: a straight line of stops with yours at [tracked]. */
internal class DemoGeometry(val stopIds: List<String>, val names: List<String>, val points: List<LatLon>, val tracked: Int) {
    /** Where a vehicle [dist] stops before your stop is (clamped to the line's start). */
    fun locationAt(dist: Double): LatLon {
        val p = (tracked - dist).coerceAtLeast(0.01)
        return LatLon(points[0].lat, DemoBaseLon + p * DemoLonStep)
    }

    fun stops(): Map<String, FutarStop> = stopIds.indices.associate { i ->
        stopIds[i] to FutarStop(stopIds[i], points[i].lat, points[i].lon, names[i])
    }
}

internal fun demoGeometry(stop: DemoStop, dirIndex: Int): DemoGeometry {
    val d = stop.directions[dirIndex]
    val before = if (d.origin) 0 else DemoStopsBefore
    val after = if (d.terminus) 0 else DemoStopsAfter
    val n = before + 1 + after
    val ids = (0 until n).map { i -> if (i == before) stop.stopId else "${DemoFeed.Prefix}P_${stop.key}_${dirIndex}_$i" }
    val names = (0 until n).map { i ->
        when {
            i < before -> "${d.approach} ${before - i}"
            i == before -> stop.name
            else -> "${extractHeadsignDestination(d.headsign)} ${i - before}"
        }
    }
    val lat = DemoBaseLat + dirIndex * 0.003
    val points = (0 until n).map { i -> LatLon(lat, DemoBaseLon + i * DemoLonStep) }
    return DemoGeometry(ids, names, points, before)
}

/** Google's encoded-polyline format (1e-5 precision), the shape Futár's geometry arrives in. */
internal fun encodePolyline(points: List<LatLon>): String {
    val out = StringBuilder()
    var lastLat = 0L
    var lastLon = 0L
    for (p in points) {
        val lat = (p.lat * 1e5).roundToLong()
        val lon = (p.lon * 1e5).roundToLong()
        encodeValue(lat - lastLat, out)
        encodeValue(lon - lastLon, out)
        lastLat = lat
        lastLon = lon
    }
    return out.toString()
}

private fun encodeValue(value: Long, out: StringBuilder) {
    var v = if (value < 0) (value shl 1).inv() else value shl 1
    while (v >= 0x20) {
        out.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
        v = v shr 5
    }
    out.append((v + 63).toInt().toChar())
}
