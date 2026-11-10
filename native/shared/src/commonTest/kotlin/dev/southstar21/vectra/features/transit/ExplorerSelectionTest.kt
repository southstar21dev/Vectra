package dev.southstar21.vectra.features.transit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The explorer's selected departure follows later polls instead of keeping its tap-time values. */
class ExplorerSelectionTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    private val selection = ExplorerSelection(
        stopId = "BKK_CS1", stopName = "Astoria", lat = 47.49, lon = 19.06,
        routeId = "BKK_3050", shortName = "5", routeType = "BUS", headsign = "Pasaréti tér",
        color = "009FE3", textColor = "FFFFFF", tripId = "T1", platformStopId = "BKK_F1",
        distanceMeters = 120.0, arrivalMs = now + 2 * min, isPredicted = true, serviceDate = "20261109",
    )

    private fun arrival(tripId: String, at: Long, headsign: String = "Pasaréti tér", predicted: Boolean = true, platform: String? = null) =
        Arrival(arrivalMs = at, headSign = headsign, isPredicted = predicted, tripId = tripId, platformStopId = platform, serviceDate = "20261110")

    private fun board(vararg arrivals: Arrival, routeId: String = "BKK_3050") =
        NearbyDeparture(routeId, "5", "BUS", "009FE3", "FFFFFF", arrivals.toList())

    @Test fun aSlippedPredictionMovesTheHeader() {
        val r = refreshExplorerSelection(selection, listOf(board(arrival("T1", now + 6 * min))), now)
        assertEquals(now + 6 * min, r?.arrivalMs)
        assertEquals("T1", r?.tripId)
        assertEquals(true, r?.isPredicted)
    }

    @Test fun aLostPredictionStopsShowingLive() {
        val r = refreshExplorerSelection(selection, listOf(board(arrival("T1", now + 3 * min, predicted = false))), now)
        assertEquals(false, r?.isPredicted)
    }

    @Test fun aDepartedTripHandsOverToTheNextInTheSameDirection() {
        val boards = listOf(
            board(arrival("T_OTHER_DIR", now + 1 * min, headsign = "Kőbánya alsó"), arrival("T2", now + 9 * min, platform = "BKK_F1b")),
            board(arrival("T_OTHER_LINE", now + 2 * min), routeId = "BKK_3070"),
        )
        val r = refreshExplorerSelection(selection.copy(arrivalMs = now - 5 * min), boards, now)
        assertEquals("T2", r?.tripId)
        assertEquals(now + 9 * min, r?.arrivalMs)
        assertEquals("BKK_F1b", r?.platformStopId)
        assertEquals("20261110", r?.serviceDate)
    }

    @Test fun aTripPastTheGraceIsNotFollowed() {
        val boards = listOf(board(arrival("T1", now - 2 * min), arrival("T2", now + 9 * min)))
        assertEquals("T2", refreshExplorerSelection(selection, boards, now)?.tripId)
    }

    @Test fun nothingLeftInThatDirectionClearsTheSelection() =
        assertNull(refreshExplorerSelection(selection, listOf(board(arrival("T9", now + min, headsign = "Kőbánya alsó"))), now))

    @Test fun anUnloadedStopKeepsTheLatchedValues() =
        assertSame(selection, refreshExplorerSelection(selection, null, now))

    @Test fun servingStopsAreThePlatformElseTheStationsPlatforms() {
        val refs = mapOf(
            "BKK_F1" to FutarStop(id = "BKK_F1", parentStationId = "BKK_CS1"),
            "BKK_F2" to FutarStop(id = "BKK_F2", parentStationId = "BKK_CS1"),
            "BKK_F9" to FutarStop(id = "BKK_F9", parentStationId = "BKK_CS9"),
        )
        assertEquals(setOf("BKK_F1"), explorerServingStopIds(selection, refs))
        assertEquals(setOf("BKK_F1", "BKK_F2", "BKK_CS1"), explorerServingStopIds(selection.copy(platformStopId = null), refs))
    }
}
