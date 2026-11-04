package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddTrackerLogicTest {
    private fun details(variants: List<FutarRouteVariant>, stops: Map<String, FutarStop>) =
        FutarRouteDetailsResponse(data = FutarRouteDetailsData(FutarRouteDetailsEntry("r", variants), FutarStopsReferences(stops = stops)))

    @Test
    fun parseRouteDetailsFoldsPlatformsIntoStationsOnce() {
        val res = details(
            listOf(
                FutarRouteVariant(headsign = "A", stopIds = listOf("BKK_F1", "BKK_F2")),
                FutarRouteVariant(headsign = "B", stopIds = listOf("BKK_F3", "BKK_F1")),
                FutarRouteVariant(headsign = "A", stopIds = emptyList()),
            ),
            mapOf(
                "BKK_F1" to FutarStop("BKK_F1", 47.0, 19.0, "One", parentStationId = "BKK_CS1"),
                "BKK_F2" to FutarStop("BKK_F2", 47.1, 19.1, "Two", parentStationId = "BKK_CS2"),
                "BKK_F3" to FutarStop("BKK_F3", 47.2, 19.2, "Two again", parentStationId = "BKK_CS2"),
                "BKK_CS2" to FutarStop("BKK_CS2", 0.0, 0.0, "Station Two"),
            ),
        )
        val parsed = parseRouteDetails(res)
        assertEquals(listOf("A", "B"), parsed.headsigns)
        assertEquals(listOf("BKK_CS1", "BKK_CS2"), parsed.stops.map { it.id })
        assertEquals(listOf("One", "Station Two"), parsed.stops.map { it.name })
        assertEquals(47.0, parsed.stops[0].lat)
        assertTrue(parsed.polylines.isEmpty())
    }

    @Test
    fun previewVehiclesKeepOnlyRelevantPositionedTrips() {
        val res = FutarVehiclesForRouteResponse(
            data = FutarVehiclesData(
                listOf(
                    FutarVehicle("v1", tripId = "t1", location = LatLon(47.5, 19.0), bearing = 90.0),
                    FutarVehicle("v2", tripId = "t2", location = LatLon(47.5, 19.0)),
                    FutarVehicle("v3", tripId = "t1", location = LatLon(0.0, 0.0)),
                    FutarVehicle("v4", tripId = null, location = LatLon(47.5, 19.0)),
                ),
            ),
        )
        val out = previewVehiclesFrom(res, setOf("t1"))
        assertEquals(listOf(RoutePreviewVehicle("v1", 47.5, 19.0, 90.0)), out)
    }

    @Test
    fun curatedGroupsAreAllPopulated() {
        for (g in MetroView.entries) assertTrue(CuratedStops.any { it.group == g.key }, g.key)
        assertEquals(setOf("M1", "M2", "M3", "M4"), MetroRouteIds.keys)
        assertEquals("Metro M2", metroViewLabel(MetroView.M2))
        assertEquals("Popular stops", metroViewLabel(MetroView.POPULAR))
    }
}
