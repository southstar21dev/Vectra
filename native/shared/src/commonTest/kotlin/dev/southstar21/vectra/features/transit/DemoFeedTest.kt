package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.decodePolyline
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoFeedTest {
    private val now = 1_800_000_000_000L

    @Test
    fun polylineRoundTrips() {
        val g = demoGeometry(DemoLines.first().stops.first(), 0)
        val decoded = decodePolyline(encodePolyline(g.points))
        assertEquals(g.points.size, decoded.size)
        assertTrue(g.points.zip(decoded).all { (a, b) -> abs(a.lat - b.lat) < 1e-5 && abs(a.lon - b.lon) < 1e-5 })
    }

    @Test
    fun vehiclesLandTheRequestedStopsOut() {
        val stop = DemoLines.first { it.key == "far" }.stops.first()
        val g = demoGeometry(stop, 0)
        for (dist in listOf(0.6, 1.4, 2.9, 7.4)) {
            val p = routeProgress(g.points, g.points, g.locationAt(dist), g.tracked)
            val read = (g.tracked - p.vehicleIndex) + (1.0 - (p.segmentFraction ?: 0.0))
            assertTrue(abs(read - dist) < 0.05, "asked $dist, read $read")
        }
    }

    @Test
    fun arrivalsParseForEveryLiveDemoStop() = runTest {
        for (line in DemoLines) for (stop in line.stops) {
            if (stop.behaviour != DemoBehaviour.Live) continue
            val arrivals = parseStopArrivals(DemoFeed.arrivals(stop.stopId, now), line.routeId, null, now)
            val expected = stop.directions.sumOf { d -> if (d.cycleMs > 0) 3 else d.trips.size }
            assertEquals(expected, arrivals.size, stop.key)
        }
    }
}
