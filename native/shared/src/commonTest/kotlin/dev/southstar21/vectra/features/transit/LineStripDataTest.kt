package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineStripDataTest {
    private val now = 1_800_000_000_000L
    private val points = (0..6).map { LatLon(47.5, 19.0 + it * 0.005) }
    private val stops = points.mapIndexed { i, p -> RouteMapStop("S$i", p.lat, p.lon, isTracked = false) }

    /** A direction along [points] with your stop at [tracked], its vehicle (live or estimated) at [at]. */
    private fun direction(tracked: Int, at: LatLon?, live: Boolean): DirectionView {
        val vehicle = at?.let { RouteMapVehicle("v", it, isApproximate = !live) }
        return DirectionView(
            tripId = "t",
            stops = stops,
            polyline = points,
            vehicles = if (live && vehicle != null) listOf(vehicle) else emptyList(),
            arrival = Arrival(arrivalMs = now + 5 * 60_000L, headSign = "End", isPredicted = false, tripId = "t"),
            upcoming = emptyList(),
            trackedIndex = tracked,
            progress = routeProgress(points, points, at, tracked),
            stopTimes = emptyList(),
            approxVehicle = if (!live) vehicle else null,
        )
    }

    @Test
    fun metroEstimateIsPlacedLikeALiveVehicle() {
        val between = LatLon(47.5, 19.0 + 1.5 * 0.005)
        val d = direction(tracked = 4, at = between, live = false).toStripDirection()
        val dist = assertNotNull(d.vehicleDistance)
        assertTrue(dist in 2.0..3.0, "about 2.5 stops away, was $dist")
    }

    @Test
    fun anEstimateAtTheFirstStopIsNotAWaitingTrain() {
        // Before every departure the timetable estimate sits at the trip's start.
        val d = direction(tracked = 0, at = points[0], live = false).toStripDirection()
        assertNull(d.vehicleDistance)
        val v = buildStripModel(listOf(d), now, metro = true, frozen = false).vehicles.single()
        assertTrue(v.sub != "waiting")
    }

    @Test
    fun aReportingVehicleAtTheFirstStopWaits() {
        val d = direction(tracked = 0, at = points[0], live = true).toStripDirection()
        assertEquals(0.0, d.vehicleDistance)
        assertEquals("waiting", buildStripModel(listOf(d), now, metro = false, frozen = false).vehicles.single().sub)
    }

    @Test
    fun descriptionSaysNowForEveryDueVehicle() {
        val model = StripModel(
            vehicles = listOf(StripVehicle(3f, 1, "NOW"), StripVehicle(5f, -1, "4'")),
            leftEnd = "A",
            rightEnd = "B",
        )
        assertEquals("to B now, to A in 4 min", stripDescription(model))
    }
}
