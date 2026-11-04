package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LivePositionTest {
    private fun arrival(tripId: String, headSign: String) = Arrival(arrivalMs = 1L, headSign = headSign, isPredicted = true, tripId = tripId)

    @Test fun firstTripPerDirection() = assertEquals(
        listOf("T1", "T3"),
        trackedTripIds(listOf(arrival("T1", "Keleti pályaudvar"), arrival("T2", "Keleti pályaudvar"), arrival("T3", "Kelenföld vasútállomás"))),
    )

    @Test fun singleTripForDirectionTracker() = assertEquals(listOf("T1"), trackedTripIds(listOf(arrival("T1", "Keleti pályaudvar"))))

    @Test fun skipsBlankTripsAndCapsAtThree() = assertEquals(
        listOf("T1", "T2", "T3"),
        trackedTripIds(listOf(arrival("", "Keleti pályaudvar"), arrival("T1", "Keleti pályaudvar"), arrival("T2", "B"), arrival("T3", "C"), arrival("T4", "D"))),
    )

    @Test fun matchingVehiclesOnTrackedTrips() {
        val vehicles = listOf(
            FutarVehicle(vehicleId = "V1", tripId = "T1", routeId = "R", location = LatLon(1.0, 1.0)),
            FutarVehicle(vehicleId = "V2", tripId = "T9", routeId = "R", location = LatLon(1.0, 1.0)),
            FutarVehicle(vehicleId = "V3", routeId = "R", location = LatLon(1.0, 1.0)),
        )
        assertEquals(listOf("V1"), matchingVehicles(vehicles, listOf("T1")).map { it.vehicleId })
    }

    // ---- routeProgress ----
    private val ns = listOf(LatLon(47.5, 19.04), LatLon(47.51, 19.04), LatLon(47.52, 19.04), LatLon(47.53, 19.04))

    @Test fun progressWithoutVehicle() {
        val p = routeProgress(ns, ns, null, 2)
        assertEquals(-1, p.vehicleIndex)
        assertNull(p.stopsLeft)
        assertNull(p.vehicleFraction)
        assertCloseTo(2.0 / 3, p.trackedFraction, 5)
    }

    @Test fun progressWithVehicle() {
        val p = routeProgress(ns, ns, LatLon(47.515, 19.04), 3)
        assertEquals(2, p.vehicleIndex)
        assertEquals(2, p.stopsLeft)
        assertCloseTo(0.5, p.vehicleFraction, 1)
        assertEquals(1.0, p.trackedFraction)
        assertCloseTo(0.5, p.segmentFraction, 1)
    }

    @Test fun progressPassedTrackedStop() {
        val p = routeProgress(ns, ns, LatLon(47.525, 19.04), 1)
        assertNull(p.stopsLeft)
        assertCloseTo(0.833, p.vehicleFraction, 1)
    }

    @Test fun progressIndexFallback() = assertEquals(1.0, routeProgress(ns, emptyList(), null, 3).trackedFraction)

    // ---- stripLabels ----
    private val names = listOf("A", "B", "C", "D")

    @Test fun trackedInMiddle() = assertEquals(StripLabels("A", "C · tracked", "D"), stripLabels(names, 2))
    @Test fun trackedOwnsStart() = assertEquals(StripLabels("A · tracked", null, "D"), stripLabels(names, 0))
    @Test fun trackedOwnsEnd() = assertEquals(StripLabels("A", null, "D · tracked"), stripLabels(names, 3))
    @Test fun singleStopLine() = assertEquals(StripLabels("A · tracked", null, ""), stripLabels(listOf("A"), 0))

    // ---- clipRouteToTrackedStop ----
    private data class NamedStop(val lat: Double, val lon: Double, val name: String)
    private val namedStops = ns.mapIndexed { i, p -> NamedStop(p.lat, p.lon, "S$i") }
    private fun clip(polyline: List<LatLon>, stops: List<NamedStop>, idx: Int) =
        clipRouteToTrackedStop(polyline, stops, idx) { LatLon(it.lat, it.lon) }

    @Test fun clipsToTrackedStop() {
        val out = assertNotNull(clip(ns, namedStops, 2))
        assertEquals(listOf("S0", "S1", "S2"), out.stops.map { it.name })
        assertCloseTo(47.52, out.polyline.last().lat, 4)
        assertTrue(out.polyline.size < ns.size)
    }

    @Test fun clipKeepsWholeLineAtTerminus() {
        val out = clip(ns, namedStops, 3)!!
        assertEquals(4, out.stops.size)
        assertCloseTo(47.53, out.polyline.last().lat, 4)
    }

    @Test fun clipNullAtFirstStop() = assertNull(clip(ns, namedStops, 0))

    @Test fun clipNullWithoutGeometry() {
        assertNull(clip(emptyList(), namedStops, 1))
        assertNull(clip(ns, emptyList(), 1))
        assertNull(clip(ns, namedStops, 99))
    }

    // ---- approachFromVehicle ----
    private val approachStops = ns.mapIndexed { i, p -> ApproachStop("S$i", "S$i", p.lat, p.lon) }

    @Test fun approachFromVehiclePosition() {
        val out = assertNotNull(approachFromVehicle(ns, approachStops, 2, LatLon(47.505, 19.04)))
        assertEquals(listOf("S1", "S2"), out.stops.map { it.id })
        assertCloseTo(47.505, out.polyline[0].lat, 3)
        assertCloseTo(47.52, out.polyline.last().lat, 4)
    }

    @Test fun approachFromLineStart() {
        val out = approachFromVehicle(ns, approachStops, 2, null)!!
        assertEquals(listOf("S0", "S1", "S2"), out.stops.map { it.id })
        assertCloseTo(47.5, out.polyline[0].lat, 4)
    }

    @Test fun approachNullOncePassed() = assertNull(approachFromVehicle(ns, approachStops, 2, LatLon(47.525, 19.04)))

    @Test fun approachNullAtLineStart() = assertNull(approachFromVehicle(ns, approachStops, 0, LatLon(47.5, 19.04)))

    @Test fun approachNullWithoutGeometry() {
        assertNull(approachFromVehicle(emptyList(), approachStops, 1, null))
        assertNull(approachFromVehicle(ns, emptyList(), 1, null))
        assertNull(approachFromVehicle(ns, approachStops, 99, null))
    }

    // ---- resolveTrackedStopIndex ----
    private val refs = mapOf(
        "BKK_F001" to FutarStop(parentStationId = "BKK_CS001"),
        "BKK_F002" to FutarStop(parentStationId = "BKK_CS002"),
    )

    @Test fun exactPlatformId() = assertEquals(1, resolveTrackedStopIndex(listOf("BKK_F001", "BKK_F002"), refs, "BKK_F002"))
    @Test fun stationFallback() = assertEquals(1, resolveTrackedStopIndex(listOf("BKK_F001", "BKK_F002"), refs, "BKK_CS002"))

    @Test fun notFound() {
        assertEquals(-1, resolveTrackedStopIndex(listOf("BKK_F001", "BKK_F002"), refs, "BKK_F999"))
        assertEquals(-1, resolveTrackedStopIndex(emptyList(), refs, "BKK_F001"))
        assertEquals(-1, resolveTrackedStopIndex(listOf("BKK_F001"), refs, null))
    }

    // ---- approximateVehiclePosition ----
    private val ew = listOf(LatLon(47.5, 19.0), LatLon(47.5, 19.05), LatLon(47.5, 19.1))
    private fun st(dep: Long?, arr: Long?, dist: Double) = FutarTripStopTime(departureTime = dep, arrivalTime = arr, shapeDistTraveled = dist)
    private val times = listOf(st(0, 0, 0.0), st(120, 120, 5000.0), st(240, 240, 10000.0))

    @Test fun approxNullWithoutData() {
        assertNull(approximateVehiclePosition(ApproximateVehicleInput(emptyList(), ew, 60_000)))
        assertNull(approximateVehiclePosition(ApproximateVehicleInput(times, emptyList(), 60_000)))
    }

    @Test fun approxParksAtOrigin() = assertEquals(ew[0], approximateVehiclePosition(ApproximateVehicleInput(times, ew, -10_000)))

    @Test fun approxParksAtTerminus() = assertEquals(ew.last(), approximateVehiclePosition(ApproximateVehicleInput(times, ew, 250_000)))

    @Test fun approxInterpolates() {
        val p = assertNotNull(approximateVehiclePosition(ApproximateVehicleInput(times, ew, 60_000)))
        assertCloseTo(19.025, p.lon, 4)
    }

    @Test fun approxAppliesDelay() {
        val p = assertNotNull(approximateVehiclePosition(ApproximateVehicleInput(times, ew, 180_000, delayMs = 60_000)))
        assertCloseTo(19.05, p.lon, 4)
    }

    @Test fun approxUsesTerminusArrival() {
        val trip = listOf(st(0, null, 0.0), st(120, 120, 5000.0), st(240, 240, 10000.0), st(null, 300, 12000.0))
        val p = assertNotNull(approximateVehiclePosition(ApproximateVehicleInput(trip, ew, 270_000)))
        assertTrue(p.lon > 19.05)
        assertTrue(p.lon < 19.1)
        assertCloseTo(19.0917, p.lon, 3)
        assertEquals(ew.last(), approximateVehiclePosition(ApproximateVehicleInput(trip, ew, 300_000)))
    }

    // ---- stripDirection ----
    @Test fun directionNullWhenEqualsEnd() {
        assertNull(stripDirection("Széll Kálmán tér", "Széll Kálmán tér"))
        assertNull(stripDirection(" széll kálmán tér ", "Széll Kálmán tér"))
    }

    @Test fun directionWhenDiffers() =
        assertEquals("Újpalota, Szentmihályi út", stripDirection("Újpalota, Szentmihályi út", "Blaha Lujza tér M"))

    @Test fun directionNullForEmpty() {
        assertNull(stripDirection(null, "X"))
        assertNull(stripDirection("", "X"))
    }
}
