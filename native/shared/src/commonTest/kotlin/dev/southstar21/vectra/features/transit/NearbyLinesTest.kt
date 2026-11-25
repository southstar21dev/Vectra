package dev.southstar21.vectra.features.transit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NearbyLinesTest {
    private fun item(stopId: String, distance: Double, routeId: String, type: String) = RadarDepartureItem(
        id = "$stopId-$routeId",
        stopId = stopId,
        stopName = stopId,
        distanceMeters = distance,
        walkMinutes = 1,
        feasibility = Feasibility.Catchable,
        routeId = routeId,
        shortName = routeId,
        routeType = type,
        color = "000000",
        textColor = "FFFFFF",
        headsign = "H",
        arrivalMs = 1L,
        countdownMinutes = 3,
        isPredicted = true,
    )

    @Test
    fun nearestStopWinsOverModeOrder() {
        // Sorted like buildRadarDepartures: the tram (far) comes before the bus (near).
        val items = listOf(item("far-tram", 450.0, "1", "TRAM"), item("near-bus", 30.0, "7", "BUS"))
        assertEquals("near-bus", nearestStopItem(items)?.stopId)
        assertEquals(listOf("7"), nearbyTrackers(items, "near-bus").map { it.routeShortName })
    }

    @Test
    fun noItemsNoLead() {
        assertNull(nearestStopItem(emptyList()))
    }

    @Test
    fun emptyMessageSaysOfflineFirst() {
        assertEquals("Offline, departures unavailable", emptyNearbyMessage(NearbyLocationStatus.Tracking, offline = true))
        assertEquals("No departures near you right now", emptyNearbyMessage(NearbyLocationStatus.Tracking, offline = false))
        assertEquals("Allow location to see departures", emptyNearbyMessage(NearbyLocationStatus.Failed, offline = false))
    }
}
