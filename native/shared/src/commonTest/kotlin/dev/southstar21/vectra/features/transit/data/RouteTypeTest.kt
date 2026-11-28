package dev.southstar21.vectra.features.transit.data

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteTypeTest {
    // These strings are stored in trackers and sent to the feed: naming them must not change them.
    @Test fun theNamesKeepTheirStoredValues() {
        assertEquals(
            listOf("SUBWAY", "TRAM", "BUS", "TROLLEYBUS", "RAIL", "SUBURBAN_RAILWAY", "FERRY", "COACH"),
            listOf(
                RouteType.Subway, RouteType.Tram, RouteType.Bus, RouteType.Trolleybus,
                RouteType.Rail, RouteType.SuburbanRailway, RouteType.Ferry, RouteType.Coach,
            ),
        )
    }

    @Test fun theVehicleOrderPutsTheMetroFirst() {
        assertEquals(listOf("SUBWAY", "TRAM", "TROLLEYBUS", "BUS", "RAIL", "FERRY"), RouteType.Priority)
    }
}
