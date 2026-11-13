package dev.southstar21.vectra.features.transit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Trams (or the metro) replaced by buses: BKK runs those as routes of their own (BKK_VP28 "28"). */
class ReplacementBusTest {
    private val now = 1_800_000_000_000L

    @Test
    fun replacementRoutesStandInForTheirLine() {
        assertTrue(isReplacementFor("BKK_VP28", "28", "BKK_3280", "28"))
        // Paired lines: the 28A tracker takes the 28's buses, the 4 and the 6 take "4-6".
        assertTrue(isReplacementFor("BKK_VP28", "28", "BKK_3281", "28A"))
        assertTrue(isReplacementFor("BKK_VP46", "4-6", "BKK_3060", "6"))
        assertTrue(isReplacementFor("BKK_MP52", "M2", "BKK_5200", "M2"))
        // Another line's replacement, a regular bus with the same number, or no name: no.
        assertFalse(isReplacementFor("BKK_VP19", "19", "BKK_3280", "28"))
        assertFalse(isReplacementFor("BKK_0280", "28", "BKK_3280", "28"))
        assertFalse(isReplacementFor("BKK_VP28", null, "BKK_3280", "28"))
    }

    private fun stopTime(trip: String, minutes: Int, headsign: String) =
        FutarStopTime(stopId = "S", stopHeadsign = headsign, arrivalTime = now / 1000 + minutes * 60, tripId = trip)

    private val response = FutarArrivalsResponse(
        currentTime = now,
        status = "OK",
        data = FutarArrivalsData(
            entry = FutarArrivalsEntry(
                "S",
                listOf(stopTime("tram", 9, "Izraelita temető"), stopTime("bus", 3, "Teleki László tér"), stopTime("other", 1, "Pasaréti tér")),
            ),
            references = FutarReferences(
                routes = mapOf(
                    "BKK_3280" to FutarRoute("BKK_3280", "28", type = "TRAM"),
                    "BKK_VP28" to FutarRoute("BKK_VP28", "28", type = "BUS"),
                    "BKK_0050" to FutarRoute("BKK_0050", "5", type = "BUS"),
                ),
                trips = mapOf(
                    "tram" to FutarTripRef("BKK_3280", "Izraelita temető"),
                    "bus" to FutarTripRef("BKK_VP28", "Teleki László tér"),
                    "other" to FutarTripRef("BKK_0050", "Pasaréti tér"),
                ),
            ),
        ),
    )

    @Test
    fun aLinesArrivalsIncludeItsReplacementBuses() {
        val arrivals = parseStopArrivals(response, "BKK_3280", now = now, shortName = "28")
        assertEquals(listOf("bus", "tram"), arrivals.map { it.tripId })
        assertTrue(arrivals.first().isReplacementBus)
        assertFalse(arrivals.last().isReplacementBus)
    }

    @Test
    fun replacementBusesKeepTheirOwnDestinations() {
        // A tracker for one direction of the tram still sees the buses, whose ends differ.
        val arrivals = parseStopArrivals(response, "BKK_3280", headsign = "Izraelita temető", now = now, shortName = "28")
        assertEquals(listOf("bus", "tram"), arrivals.map { it.tripId })
    }

    @Test
    fun replacementAlertsAreReadFromTheirHeader() {
        fun alert(header: String, description: String = "") = TransitAlert("a", header, description)
        assertTrue(isReplacementAlert(alert("replacement bus service between Teleki László tér and Blaha Lujza tér M")))
        assertTrue(isReplacementAlert(alert("autóbuszos pótlás a Teleki László tér és a Blaha Lujza tér között")))
        assertFalse(isReplacementAlert(alert("Tram 4 runs less often", "Use the replacement bus on line 28")))
    }

    @Test
    fun replacementBusesAreDrawnAsBuses() {
        val bus = Arrival(arrivalMs = now + 3 * 60_000L, headSign = "Teleki László tér", isPredicted = true, tripId = "bus", routeId = "BKK_VP28")
        val m = buildStripModel(listOf(StripDirection("Teleki László tér", bus, null, 1.5, replacement = true)), now, metro = false, frozen = false)
        assertTrue(m.vehicles.single().replacement)
        assertTrue(m.rightBus)
        assertFalse(m.leftBus)
    }

    @Test
    fun aMetroReplacementBusIsPlacedLikeABus() {
        val bus = Arrival(arrivalMs = now + 3 * 60_000L, headSign = "Déli pályaudvar M", isPredicted = true, tripId = "bus", routeId = "BKK_MP52")
        val v = buildStripModel(listOf(StripDirection("Déli pályaudvar M", bus, null, 1.5, replacement = true)), now, metro = true, frozen = false).vehicles.single()
        assertFalse(v.metro)
        assertTrue(v.showMarker)
    }
}
