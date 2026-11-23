package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.designsystem.components.StatusTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stop-aware direction grouping, terminus filtering, and diversion/replacement auto-switching. */
class DirectionOptionsTest {
    private val now = 1_800_000_000_000L

    private val stopRefs = mapOf(
        "BKK_CS_BLAHA" to FutarStop("BKK_CS_BLAHA", name = "Blaha Lujza tér M"),
        "BKK_CS_TELEKI" to FutarStop("BKK_CS_TELEKI", name = "Teleki László tér"),
        "BKK_CS_HIDEGKUTI" to FutarStop("BKK_CS_HIDEGKUTI", name = "Hidegkuti Nándor Stadion"),
        "BKK_CS_SORGYAR" to FutarStop("BKK_CS_SORGYAR", name = "Sörgyár"),
        "BKK_CS_KOZMA" to FutarStop("BKK_CS_KOZMA", name = "Új köztemető (Kozma u.)"),
        "BKK_P_BLAHA" to FutarStop("BKK_P_BLAHA", name = "Blaha Lujza tér M", parentStationId = "BKK_CS_BLAHA"),
        "BKK_P_TELEKI_OUT" to FutarStop("BKK_P_TELEKI_OUT", name = "Teleki László tér", parentStationId = "BKK_CS_TELEKI"),
        "BKK_P_TELEKI_IN" to FutarStop("BKK_P_TELEKI_IN", name = "Teleki László tér", parentStationId = "BKK_CS_TELEKI"),
        "BKK_P_HIDEGKUTI" to FutarStop("BKK_P_HIDEGKUTI", name = "Hidegkuti Nándor Stadion", parentStationId = "BKK_CS_HIDEGKUTI"),
        "BKK_P_SORGYAR" to FutarStop("BKK_P_SORGYAR", name = "Sörgyár", parentStationId = "BKK_CS_SORGYAR"),
        "BKK_P_KOZMA" to FutarStop("BKK_P_KOZMA", name = "Új köztemető (Kozma u.)", parentStationId = "BKK_CS_KOZMA"),
    )

    // Realistic 37/37A variants during Blaha <-> Teleki replacement-bus diversion:
    // - 37 & 37A outbound both leave Teleki -> Hidegkuti -> Sörgyár (-> Kozma) with direction "0"
    // - Normal inbound goes to Blaha ("1"), diverted inbound terminates AT Teleki ("1")
    private val variants37 = listOf(
        FutarRouteVariant(
            routeId = "BKK_3371",
            headsign = "Sörgyár",
            direction = "0",
            stopIds = listOf("BKK_P_TELEKI_OUT", "BKK_P_HIDEGKUTI", "BKK_P_SORGYAR"),
        ),
        FutarRouteVariant(
            routeId = "BKK_3370",
            headsign = "Új köztemető (Kozma u.)",
            direction = "0",
            stopIds = listOf("BKK_P_TELEKI_OUT", "BKK_P_HIDEGKUTI", "BKK_P_SORGYAR", "BKK_P_KOZMA"),
        ),
        FutarRouteVariant(
            routeId = "BKK_3370",
            headsign = "Blaha Lujza tér M",
            direction = "1",
            stopIds = listOf("BKK_P_KOZMA", "BKK_P_SORGYAR", "BKK_P_HIDEGKUTI", "BKK_P_TELEKI_IN", "BKK_P_BLAHA"),
        ),
        FutarRouteVariant(
            routeId = "BKK_3371",
            headsign = "Teleki László tér",
            direction = "1",
            stopIds = listOf("BKK_P_SORGYAR", "BKK_P_HIDEGKUTI", "BKK_P_TELEKI_IN"),
        ),
    )

    @Test
    fun groupsCoDirectionalBranchesAndHidesArrivalOnlyTerminus() {
        val arrivals = FutarArrivalsResponse(
            currentTime = now,
            status = "OK",
            data = FutarArrivalsData(
                entry = FutarArrivalsEntry(
                    stopId = "BKK_CS_TELEKI",
                    stopTimes = listOf(
                        FutarStopTime(stopId = "BKK_P_TELEKI_OUT", stopHeadsign = "Sörgyár", arrivalTime = now / 1000 + 6 * 60, tripId = "t37a"),
                    ),
                    alertIds = listOf("a1"),
                ),
                references = FutarReferences(
                    routes = mapOf("BKK_3371" to FutarRoute("BKK_3371", "37A", type = "TRAM")),
                    trips = mapOf("t37a" to FutarTripRef("BKK_3371", "Sörgyár", directionId = "0")),
                    alerts = mapOf(
                        "a1" to FutarAlert(
                            id = "a1",
                            header = FutarTranslations(mapOf("en" to "Replacement bus service between Teleki László tér and Blaha Lujza tér M")),
                            routeIds = listOf("BKK_3370", "BKK_3371"),
                        ),
                    ),
                ),
            ),
        )

        val result = buildStopDirectionOptions(
            StopDirectionContext(
                stopId = "BKK_CS_TELEKI",
                routeId = "BKK_3370",
                routeShortName = "37",
                routeType = "TRAM",
                variants = variants37,
                stopRefs = stopRefs,
                arrivalsData = arrivals,
                now = now,
            ),
        )

        // Teleki László tér is arrival-only at Teleki László tér, so it is hidden!
        assertEquals(listOf("Teleki László tér"), result.hiddenTermini)
        assertEquals(3, result.options.size)
        assertEquals("", result.options[0].value)

        // 37A (Sörgyár) and 37 (Új köztemető) grouped into one outbound direction with live ETA.
        val outbound = result.options[1]
        assertEquals("Sörgyár / Új köztemető (Kozma u.)", outbound.value)
        assertEquals(6, outbound.liveMinutes)
        assertFalse(outbound.muted)
        assertEquals("37A, 37, via Hidegkuti Nándor Stadion", outbound.subtitle)

        // Inbound Blaha Lujza tér M has no trams and an active replacement alert -> muted with badge.
        val inbound = result.options[2]
        assertEquals("Blaha Lujza tér M", inbound.value)
        assertTrue(inbound.muted)
        assertEquals("Replacement bus", inbound.badge)
        assertEquals(StatusTone.Sky, inbound.badgeTone)
        assertNull(inbound.liveMinutes)
    }

    @Test
    fun groupedHeadsignMatchesBothBranches() {
        val grouped = "Sörgyár / Új köztemető (Kozma u.)"
        assertTrue(isHeadsignMatch(grouped, "Sörgyár"))
        assertTrue(isHeadsignMatch(grouped, "Új köztemető (Kozma u.)"))
        assertFalse(isHeadsignMatch(grouped, "Blaha Lujza tér M"))
    }

    @Test
    fun legacySingleBranchResolvesToGroupedOption() {
        val result = buildStopDirectionOptions(
            StopDirectionContext(
                stopId = "BKK_CS_TELEKI",
                routeId = "BKK_3370",
                routeShortName = "37",
                routeType = "TRAM",
                variants = variants37,
                stopRefs = stopRefs,
            ),
        )
        assertEquals("Sörgyár / Új köztemető (Kozma u.)", resolveDirectionValue("Sörgyár", result.options))
        assertEquals("Sörgyár / Új köztemető (Kozma u.)", resolveDirectionValue("Új köztemető (Kozma u.)", result.options))
    }

    @Test
    fun shortTurnedTramAutoMatchesByDirectionId() {
        val dirId = targetDirectionIdFor("Blaha Lujza tér M", variants37, "BKK_CS_HIDEGKUTI", stopRefs)
        assertEquals("1", dirId)

        val response = FutarArrivalsResponse(
            currentTime = now,
            status = "OK",
            data = FutarArrivalsData(
                entry = FutarArrivalsEntry(
                    stopId = "BKK_CS_HIDEGKUTI",
                    stopTimes = listOf(
                        FutarStopTime(stopId = "BKK_P_HIDEGKUTI", stopHeadsign = "Teleki László tér", arrivalTime = now / 1000 + 4 * 60, tripId = "shortTurn"),
                        FutarStopTime(stopId = "BKK_P_HIDEGKUTI", stopHeadsign = "Sörgyár", arrivalTime = now / 1000 + 2 * 60, tripId = "outbound"),
                    ),
                ),
                references = FutarReferences(
                    routes = mapOf("BKK_3371" to FutarRoute("BKK_3371", "37A", type = "TRAM")),
                    trips = mapOf(
                        "shortTurn" to FutarTripRef("BKK_3371", "Teleki László tér", directionId = "1"),
                        "outbound" to FutarTripRef("BKK_3371", "Sörgyár", directionId = "0"),
                    ),
                ),
            ),
        )

        val matched = parseStopArrivals(response, "BKK_3370", headsign = "Blaha Lujza tér M", now = now, shortName = "37", targetDirectionId = dirId)
        assertEquals(listOf("shortTurn"), matched.map { it.tripId })
    }

    @Test
    fun sharedCorridorReplacementBusMatchesMemberLines() {
        assertTrue(isReplacementFor("BKK_VP28", "28-37", "BKK_3370", "37"))
        assertTrue(isReplacementFor("BKK_VP28", "28-37", "BKK_3371", "37A"))
        assertTrue(isReplacementFor("BKK_VP28", "28-37", "BKK_3280", "28"))
        assertFalse(isReplacementFor("BKK_VP28", "28-37", "BKK_3040", "4"))
    }
}
