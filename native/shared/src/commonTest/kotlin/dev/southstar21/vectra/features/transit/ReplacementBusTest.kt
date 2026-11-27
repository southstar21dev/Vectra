package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.features.transit.data.Arrival
import dev.southstar21.vectra.features.transit.data.FutarArrivalsData
import dev.southstar21.vectra.features.transit.data.FutarArrivalsEntry
import dev.southstar21.vectra.features.transit.data.FutarArrivalsResponse
import dev.southstar21.vectra.features.transit.data.FutarReferences
import dev.southstar21.vectra.features.transit.data.FutarRoute
import dev.southstar21.vectra.features.transit.data.FutarRouteVariant
import dev.southstar21.vectra.features.transit.data.FutarStopTime
import dev.southstar21.vectra.features.transit.data.FutarTripRef
import dev.southstar21.vectra.features.transit.data.TransitAlert
import dev.southstar21.vectra.features.transit.live.trackedTripIds
import dev.southstar21.vectra.features.transit.strip.StripDirection
import dev.southstar21.vectra.features.transit.strip.buildStripModel
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

    // Tram 6 replaced by VP46: the tracker picked the Újbuda side, buses run both ways.
    private val picked = "Újbuda-központ M / Móricz Zs. körtér M"
    private val others = listOf("Széll Kálmán tér M")

    private fun bus(headsign: String, trip: String = "bus", minutes: Int = 3) =
        Arrival(arrivalMs = now + minutes * 60_000L, headSign = headsign, isPredicted = true, tripId = trip, routeId = "BKK_VP46")

    @Test
    fun aBusToThePickedTerminusIsTheSameDirection() {
        assertFalse(isOtherDirection(bus("Móricz Zsigmond körtér M"), picked, others))
        // Any part of a compound direction counts.
        assertFalse(isOtherDirection(bus("Újbuda-központ M"), picked, others))
    }

    @Test
    fun aBusToTheOtherTerminusIsTheOtherDirection() {
        assertTrue(isOtherDirection(bus("Széll Kálmán tér M"), picked, others))
    }

    @Test
    fun theHeadsignOfThePickedDirectionWinsOverTheOtherOne() {
        // A terminus both directions list (a loop): it is the picked way, not flagged.
        assertFalse(isOtherDirection(bus("Újbuda-központ M"), picked, listOf("Újbuda-központ M", "Széll Kálmán tér M")))
    }

    @Test
    fun anUnknownDirectionIsNotLabelled() {
        assertFalse(isOtherDirection(bus("Somewhere else"), picked, others))
        assertFalse(isOtherDirection(bus("Széll Kálmán tér M"), picked, emptyList()))
        assertFalse(isOtherDirection(bus("Széll Kálmán tér M"), null, others))
        assertFalse(isOtherDirection(bus("Széll Kálmán tér M"), "", others))
    }

    @Test
    fun anAbbreviatedGivenNameStillMatches() {
        assertTrue(isHeadsignMatch("Móricz Zs. körtér M", "Móricz Zsigmond körtér M"))
        assertTrue(isHeadsignMatch("Móricz Zsigmond körtér M", "Móricz Zs. körtér M"))
        assertFalse(isHeadsignMatch("Móricz Zs. körtér M", "Széll Kálmán tér M"))
    }

    private fun busesResponse(vararg trips: Triple<String, String, String>, minutes: List<Int>) = FutarArrivalsResponse(
        currentTime = now,
        status = "OK",
        data = FutarArrivalsData(
            entry = FutarArrivalsEntry("S", trips.mapIndexed { i, (id, headsign, _) -> stopTime(id, minutes[i], headsign) }),
            references = FutarReferences(
                routes = mapOf("BKK_3060" to FutarRoute("BKK_3060", "6", type = "TRAM"), "BKK_VP46" to FutarRoute("BKK_VP46", "4-6", type = "BUS")),
                trips = trips.associate { (id, headsign, dir) -> id to FutarTripRef("BKK_VP46", headsign, dir) },
            ),
        ),
    )

    private fun parse(data: FutarArrivalsResponse, target: String?, otherHeadsigns: List<String> = others) =
        parseStopArrivals(data, "BKK_3060", picked, now, "6", targetDirectionId = target, otherHeadsigns = otherHeadsigns)

    @Test
    fun bothDirectionsDueAreBothListedAndTheOtherIsFlagged() {
        // The ids line up with the tram variants (picked = "0"): the other way used to be dropped.
        val data = busesResponse(Triple("toSzell", "Széll Kálmán tér M", "1"), Triple("toUjbuda", "Újbuda-központ M", "0"), minutes = listOf(2, 5))
        val arrivals = parse(data, "0")
        assertEquals(listOf("toSzell", "toUjbuda"), arrivals.map { it.tripId })
        assertEquals(listOf(true, false), arrivals.map { it.otherDirection })
    }

    @Test
    fun bothDirectionsDueWithSwappedIdsAreFlaggedByHeadsign() {
        val data = busesResponse(Triple("toSzell", "Széll Kálmán tér M", "0"), Triple("toUjbuda", "Újbuda-központ M", "1"), minutes = listOf(2, 5))
        val arrivals = parse(data, "0")
        assertEquals(listOf("toSzell", "toUjbuda"), arrivals.map { it.tripId })
        assertEquals(listOf(true, false), arrivals.map { it.otherDirection })
    }

    @Test
    fun aSameWayBusWithAMisalignedIdIsNotFlagged() {
        // Picked "Izraelita temető" is "0", but the bus to the end of the replaced section carries "1".
        val data = busesResponse(Triple("short", "Teleki László tér", "1"), minutes = listOf(3))
        val arrivals = parseStopArrivals(
            data, "BKK_3060", "Izraelita temető", now, "6", targetDirectionId = "0", otherHeadsigns = listOf("Blaha Lujza tér M"),
        )
        assertEquals(listOf("short"), arrivals.map { it.tripId })
        assertFalse(arrivals.single().otherDirection)
    }

    @Test
    fun aLoneOtherWayBusIsKeptAndFlagged() {
        val data = busesResponse(Triple("toSzell", "Széll Kálmán tér M", "1"), minutes = listOf(2))
        val arrivals = parse(data, "0")
        assertEquals(listOf(true), arrivals.map { it.otherDirection })
        // Without the route's variants nothing is known: listed, not flagged.
        assertFalse(parse(data, null, emptyList()).single().otherDirection)
    }

    @Test
    fun theTripFollowedIsAPickedDirectionOneEvenWhenTheOtherWayComesFirst() {
        val other = bus("Széll Kálmán tér M", "toSzell", 2).copy(otherDirection = true)
        val same = bus("Újbuda-központ M", "toUjbuda", 5)
        assertEquals(listOf("toUjbuda", "toSzell"), trackedTripIds(listOf(other, same)))
    }

    @Test
    fun theFlagReachesTheStripModel() {
        val b = bus("Széll Kálmán tér M").copy(otherDirection = true)
        val d = StripDirection("Széll Kálmán tér M", b, null, 1.5, replacement = true, otherDirection = true)
        val m = buildStripModel(listOf(d), now, metro = false, frozen = false)
        assertTrue(m.rightOther)
        assertFalse(m.leftOther)
        val plain = buildStripModel(listOf(StripDirection("Széll Kálmán tér M", b, null, 1.5, replacement = true)), now, metro = false, frozen = false)
        assertFalse(plain.rightOther)
    }

    @Test
    fun variantsGiveTheOtherDirectionsHeadsigns() {
        val variants = listOf(
            FutarRouteVariant(headsign = "Móricz Zsigmond körtér M", direction = "0"),
            FutarRouteVariant(headsign = "Széll Kálmán tér M", direction = "1"),
        )
        val target = targetDirectionIdFor(picked, variants)
        assertEquals("0", target)
    }
}
