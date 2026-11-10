package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.network.VectraJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TransitTracker carries already-parsed stops (parsed at the repository), so there is no JSON-string case. */
class TransitUtilsTest {
    private val now = 1_800_000_000_000L
    private val ctx = AlertContext(routeId = "BKK_9220", routeShortName = "922", routeType = "BUS", stopId = "BKK_F04788")

    private data class A(
        val id: String,
        val routeIds: List<String>? = null,
        val stopIds: List<String>? = null,
        val vehicleRouteTypes: List<String>? = null,
        val start: Double? = null,
        val end: Double? = null,
        val effectType: String? = null,
    )

    private fun responseWithRefAlerts(vararg alerts: A): FutarArrivalsResponse {
        val refs = alerts.associate { a ->
            a.id to FutarAlert(
                id = a.id, start = a.start, end = a.end, routeIds = a.routeIds, stopIds = a.stopIds,
                vehicleRouteTypes = a.vehicleRouteTypes,
                routes = (a.routeIds ?: emptyList()).map { FutarAlertRoute(it, a.effectType) },
                header = FutarTranslations(someTranslation = "header-${a.id}"),
                description = FutarTranslations(someTranslation = "desc-${a.id}"),
            )
        }
        return FutarArrivalsResponse(
            currentTime = now, status = "OK",
            data = FutarArrivalsData(
                entry = FutarArrivalsEntry(stopId = ctx.stopId, stopTimes = emptyList(), alertIds = emptyList()),
                references = FutarReferences(
                    alerts = refs,
                    routes = mapOf(
                        "BKK_9220" to FutarRoute("BKK_9220", "922", "", type = "BUS", color = "000000", textColor = "FFFFFF"),
                        "BKK_VP51" to FutarRoute("BKK_VP51", "51", "", type = "TRAM", color = "FFD800", textColor = "000000"),
                        "BKK_MV123" to FutarRoute("BKK_MV123", "S60", "", type = "RAIL", color = "0055AA", textColor = "FFFFFF"),
                    ),
                    stops = mapOf(ctx.stopId to FutarStop(id = ctx.stopId, name = "Test stop", lat = 47.5, lon = 19.0, parentStationId = "BKK_F00001")),
                ),
            ),
        )
    }

    private fun ids(res: FutarArrivalsResponse?, c: AlertContext = ctx, r: ((String) -> String?)? = null, tr: ((String) -> String?)? = null) =
        parseAlerts(res, c, now, r, tr).map { it.id }

    @Test fun stripHtmlRemovesTags() = assertEquals(
        "Tram 2B does not operate & is replaced by bus 223E.",
        stripHtml("<ul><li>Tram <strong>2B</strong> does not operate &amp; is replaced by bus <i>223E</i>.</li></ul>"),
    )

    @Test fun stripHtmlBreaksAndEntities() {
        assertEquals("line 1 line 2 x", stripHtml("line 1<br>line 2&nbsp;&nbsp;x"))
        assertEquals("M3 – megszűnik", stripHtml("M3 &#8211; megszűnik"))
    }

    @Test fun stripHtmlDecodesEmojiAndKeepsInvalidEntities() {
        // Above 0xFFFF: a surrogate pair, where Char(code) used to throw during composition.
        assertEquals("Bus \uD83D\uDE8C replaced", stripHtml("Bus &#128652; replaced"))
        assertEquals("&#55357; &#99999999;", stripHtml("&#55357; &#99999999;"))
    }

    @Test fun normalizesSearchText() {
        assertEquals("szell kalman ter", normalizeSearchText("Széll Kálmán tér"))
        assertEquals("hosok tere", normalizeSearchText("Hősök tere"))
        assertEquals("deak ferenc ter", normalizeSearchText("Deák Ferenc tér"))
    }

    // ---- parseAlerts ----
    @Test fun alertsNothingWithoutData() = assertEquals(emptyList(), parseAlerts(null, ctx, now))
    @Test fun alertsRouteScoped() = assertEquals(listOf("A1"), ids(responseWithRefAlerts(A("A1", routeIds = listOf("BKK_9220"), start = 1.0))))
    @Test fun alertsOtherLineHidden() = assertEquals(emptyList(), ids(responseWithRefAlerts(A("A-other", routeIds = listOf("BKK_9230"), start = 1.0))))

    private val tramCtx = ctx.copy(routeId = "BKK_VP51", routeShortName = "51", routeType = "TRAM")
    private val resolver: (String) -> String? = { if (it == "BKK_3510") "51" else null }

    @Test fun alertsShortNameMatch() = assertEquals(
        listOf("A-mismatch"),
        ids(responseWithRefAlerts(A("A-mismatch", routeIds = listOf("BKK_3510"), vehicleRouteTypes = listOf("TRAM"), start = 1.0)), tramCtx, resolver),
    )

    @Test fun alertsShortNameConflictingType() = assertEquals(
        emptyList(),
        ids(responseWithRefAlerts(A("A-bus51", routeIds = listOf("BKK_3510"), vehicleRouteTypes = listOf("BUS"), start = 1.0)), tramCtx, resolver),
    )

    @Test fun alertsStopAttached() {
        assertEquals(listOf("A-stop"), ids(responseWithRefAlerts(A("A-stop", stopIds = listOf(ctx.stopId), start = 1.0))))
        assertEquals(listOf("A-parent"), ids(responseWithRefAlerts(A("A-parent", stopIds = listOf("BKK_F00001"), start = 1.0))))
    }

    @Test fun alertsStopScopedTypeExcluded() =
        assertEquals(emptyList(), ids(responseWithRefAlerts(A("A-rail", stopIds = listOf("BKK_F00001"), vehicleRouteTypes = listOf("RAIL"), start = 1.0))))

    @Test fun alertsStopScopedTypeIncluded() =
        assertEquals(listOf("A-bus"), ids(responseWithRefAlerts(A("A-bus", stopIds = listOf("BKK_F00001"), vehicleRouteTypes = listOf("BUS"), start = 1.0))))

    @Test fun alertsStopScopedGeneric() =
        assertEquals(listOf("A-generic"), ids(responseWithRefAlerts(A("A-generic", stopIds = listOf("BKK_F00001"), start = 1.0))))

    @Test fun alertsStopScopedRouteTypeFallback() =
        assertEquals(emptyList(), ids(responseWithRefAlerts(A("A-rail-fb", stopIds = listOf("BKK_F00001"), routeIds = listOf("BKK_MV123"), start = 1.0))))

    @Test fun alertsExtraTypeResolver() = assertEquals(
        emptyList(),
        ids(responseWithRefAlerts(A("A-external", stopIds = listOf("BKK_F00001"), routeIds = listOf("BKK_XTRAIN"), start = 1.0)), tr = { if (it == "BKK_XTRAIN") "RAIL" else null }),
    )

    @Test fun alertsRoutesOnlyFormIsNotAgencyWide() {
        // Another line named only in the rich routes:[{routeId}] form, with no routeIds and no type to go by.
        val other = FutarAlert(
            id = "A-other", routes = listOf(FutarAlertRoute("BKK_OTHER", null)),
            header = FutarTranslations(someTranslation = "h"), description = FutarTranslations(someTranslation = "d"),
        )
        val base = responseWithRefAlerts()
        val data = base.data!!
        val res = base.copy(data = data.copy(references = data.references!!.copy(alerts = mapOf("A-other" to other))))
        assertEquals(emptyList(), ids(res))
    }

    @Test fun alertsAgencyTypeExcluded() =
        assertEquals(emptyList(), ids(responseWithRefAlerts(A("A-agency-rail", start = 1.0, vehicleRouteTypes = listOf("RAIL")))))

    @Test fun alertsAgencySortedLast() = assertEquals(
        listOf("A-noservice", "A-agency"),
        ids(responseWithRefAlerts(A("A-noservice", routeIds = listOf("BKK_9220"), start = 1.0, effectType = "NO_SERVICE"), A("A-agency", start = 1.0))),
    )

    @Test fun alertsValidityWindow() = assertEquals(
        listOf("A-active"),
        ids(
            responseWithRefAlerts(
                A("A-active", routeIds = listOf("BKK_9220"), start = 1.0, end = now / 1000.0 + 1000),
                A("A-expired", routeIds = listOf("BKK_9220"), start = 1.0, end = now / 1000.0 - 1),
            ),
        ),
    )

    @Test fun alertsHeaderAndDescription() {
        val a = parseAlerts(responseWithRefAlerts(A("A1", routeIds = listOf("BKK_9220"), start = 1.0)), ctx, now)[0]
        assertEquals("header-A1", a.header)
        assertEquals("desc-A1", a.description)
    }

    // ---- isRouteMatch / formatRouteDisplayName ----
    @Test fun matches37() {
        assertTrue(isRouteMatch("BKK_3370", "BKK_3371"))
        assertTrue(isRouteMatch("BKK_3371", "BKK_3370"))
        assertTrue(isRouteMatch("BKK_3370", "BKK_3370"))
    }

    @Test fun matches28() {
        assertTrue(isRouteMatch("BKK_3280", "BKK_3281"))
        assertTrue(isRouteMatch("BKK_3281", "BKK_3280"))
        assertTrue(isRouteMatch("OTHER_28", "BKK_3281", "28", "28A"))
    }

    @Test fun matches4And6() {
        assertTrue(isRouteMatch("BKK_3040", "BKK_3060"))
        assertTrue(isRouteMatch("BKK_3060", "BKK_3040"))
        assertTrue(isRouteMatch("OTHER_4", "BKK_3060", "4", "6"))
        assertTrue(isRouteMatch("OTHER_6", "BKK_3040", "6", "4"))
        assertTrue(isRouteMatch("BKK_3046", "BKK_3040"))
        assertTrue(isRouteMatch("BKK_3046", "BKK_3060"))
        assertTrue(isRouteMatch("volan_H5", "BKK_H5"))
    }

    @Test fun noCrossGroupMatch() {
        assertFalse(isRouteMatch("BKK_3370", "BKK_9220"))
        assertFalse(isRouteMatch("BKK_3040", "BKK_3370"))
        assertFalse(isRouteMatch("BKK_3280", "BKK_3060"))
        assertFalse(isRouteMatch("BKK_H5", "BKK_3040"))
    }

    @Test fun combinedDisplayNames() {
        assertEquals("37 / 37A", formatRouteDisplayName("37", "BKK_3370"))
        assertEquals("37 / 37A", formatRouteDisplayName("37A", "BKK_3371"))
        assertEquals("37/37A", formatRouteDisplayName("37", "BKK_3370", true))
        assertEquals("28 / 28A", formatRouteDisplayName("28", "BKK_3280"))
        assertEquals("28 / 28A", formatRouteDisplayName("28A", "BKK_3281"))
        assertEquals("28/28A", formatRouteDisplayName("28", "BKK_3280", true))
        assertEquals("4 / 6", formatRouteDisplayName("4", "BKK_3040"))
        assertEquals("4 / 6", formatRouteDisplayName("6", "BKK_3060"))
        assertEquals("4/6", formatRouteDisplayName("4", "BKK_3040", true))
    }

    @Test fun normalDisplayNames() {
        assertEquals("922", formatRouteDisplayName("922", "BKK_9220"))
        assertEquals("M3", formatRouteDisplayName("M3", "BKK_5300"))
        assertEquals("5", formatRouteDisplayName("5", "BKK_0050"))
    }

    // ---- headsigns ----
    @Test fun extractsDestinations() {
        assertEquals("Sörgyár", extractHeadsignDestination("Salgótarjáni utca ► Sörgyár"))
        assertEquals("Kőbánya felső", extractHeadsignDestination("Kőbánya alsó vá. > Kőbánya felső"))
        assertEquals("Blaha Lujza tér M", extractHeadsignDestination("Blaha Lujza tér M"))
    }

    @Test fun viaHeadsignMatches() {
        assertTrue(isHeadsignMatch("Sörgyár", "Salgótarjáni utca ► Sörgyár", "Sörgyár"))
        assertTrue(isHeadsignMatch("sörgyár", "Salgótarjáni utca ► Sörgyár", "Sörgyár"))
        assertTrue(isHeadsignMatch("Sörgyár", "Salgótarjáni utca ► Sörgyár", null))
    }

    @Test fun canonicalTripHeadsignMatches() {
        assertTrue(isHeadsignMatch("Új köztemető (Kozma u.)", "Új köztemető", "Új köztemető (Kozma u.)"))
        assertTrue(isHeadsignMatch("Széll Kálmán tér M", "Széll Kálmán tér", "Széll Kálmán tér M"))
    }

    @Test fun emptyTargetMatchesAll() {
        assertTrue(isHeadsignMatch("", "Salgótarjáni utca ► Sörgyár", "Sörgyár"))
        assertTrue(isHeadsignMatch(null, "Blaha Lujza tér M", "Blaha Lujza tér M"))
    }

    @Test fun conflictingDestinations() {
        assertFalse(isHeadsignMatch("Blaha Lujza tér M", "Salgótarjáni utca ► Sörgyár", "Sörgyár"))
        assertFalse(isHeadsignMatch("Széll Kálmán tér M", "Újbuda-központ M", "Újbuda-központ M"))
    }

    // ---- parseStopArrivals ----
    private val futureSec = now / 1000 + 300

    @Test fun parses37And37A() {
        val res = FutarArrivalsResponse(
            currentTime = now, status = "OK",
            data = FutarArrivalsData(
                entry = FutarArrivalsEntry(
                    stopId = "BKK_F01234",
                    stopTimes = listOf(
                        FutarStopTime(stopId = "BKK_F01234", tripId = "TRIP_37A_1", stopHeadsign = "Blaha Lujza tér M", predictedArrivalTime = futureSec),
                        FutarStopTime(stopId = "BKK_F01234", tripId = "TRIP_37_1", stopHeadsign = "Új köztemető (Kozma utca)", predictedArrivalTime = futureSec + 300),
                    ),
                ),
                references = FutarReferences(
                    trips = mapOf("TRIP_37A_1" to FutarTripRef(routeId = "BKK_3371"), "TRIP_37_1" to FutarTripRef(routeId = "BKK_3370")),
                    routes = mapOf(
                        "BKK_3371" to FutarRoute("BKK_3371", "37A", "", type = "TRAM", color = "FFD800", textColor = "000000"),
                        "BKK_3370" to FutarRoute("BKK_3370", "37", "", type = "TRAM", color = "FFD800", textColor = "000000"),
                    ),
                ),
            ),
        )
        val arrivals = parseStopArrivals(res, "BKK_3370", now = now)
        assertEquals(2, arrivals.size)
        assertEquals("37A", arrivals[0].routeShortName)
        assertEquals("BKK_3371", arrivals[0].routeId)
        assertEquals("Blaha Lujza tér M", arrivals[0].headSign)
        assertEquals("37", arrivals[1].routeShortName)
        assertEquals("BKK_3370", arrivals[1].routeId)
        assertEquals("Új köztemető (Kozma utca)", arrivals[1].headSign)
    }

    @Test fun matchesViaSignDirection() {
        val res = FutarArrivalsResponse(
            currentTime = now, status = "OK",
            data = FutarArrivalsData(
                entry = FutarArrivalsEntry(
                    stopId = "BKK_CS056232",
                    stopTimes = listOf(
                        FutarStopTime(stopId = "BKK_CS056232", tripId = "TRIP_VIA_1", stopHeadsign = "Salgótarjáni utca ► Sörgyár", predictedArrivalTime = futureSec),
                        FutarStopTime(stopId = "BKK_CS056232", tripId = "TRIP_OTHER_1", stopHeadsign = "Blaha Lujza tér M", predictedArrivalTime = futureSec + 120),
                    ),
                ),
                references = FutarReferences(
                    trips = mapOf(
                        "TRIP_VIA_1" to FutarTripRef(routeId = "BKK_3371", tripHeadsign = "Sörgyár"),
                        "TRIP_OTHER_1" to FutarTripRef(routeId = "BKK_3371", tripHeadsign = "Blaha Lujza tér M"),
                    ),
                    routes = mapOf("BKK_3371" to FutarRoute("BKK_3371", "37A", "", type = "TRAM", color = "FFD800", textColor = "000000")),
                ),
            ),
        )
        val arrivals = parseStopArrivals(res, "BKK_3370", "Sörgyár", now)
        assertEquals(1, arrivals.size)
        assertEquals("TRIP_VIA_1", arrivals[0].tripId)
        assertEquals("Salgótarjáni utca ► Sörgyár", arrivals[0].headSign)
        assertEquals("Sörgyár", arrivals[0].tripHeadsign)
    }

    /** A last metro after midnight belongs to the previous service day; its trip-details must ask for that day. */
    @Test fun arrivalsCarryTheTripsServiceDate() {
        val json = """
            {"currentTime":$now,"status":"OK","data":{
              "entry":{"stopId":"BKK_F1","stopTimes":[
                {"stopId":"BKK_F1","tripId":"T_LATE","stopHeadsign":"Örs vezér tere","predictedArrivalTime":$futureSec,"serviceDate":"20261109"},
                {"stopId":"BKK_F1","tripId":"T_NUM","stopHeadsign":"Örs vezér tere","predictedArrivalTime":${futureSec + 60},"serviceDate":20261110}
              ]},
              "references":{"trips":{"T_LATE":{"routeId":"BKK_5200"},"T_NUM":{"routeId":"BKK_5200"}}}}}
        """.trimIndent()
        val res = VectraJson.decodeFromString(FutarArrivalsResponse.serializer(), json)
        val arrivals = parseStopArrivals(res, "BKK_5200", now = now)
        assertEquals(listOf("20261109", "20261110"), arrivals.map { it.serviceDate })
        val boards = parseAllNearbyBoards(res, null)
        assertEquals(listOf("20261109", "20261110"), boards.single().arrivals.map { it.serviceDate })
        assertEquals(TripDetailsKey("T_LATE", "20261109"), tripDetailsKey("T_LATE", arrivals[0].serviceDate))
    }

    @Test fun aTripDetailsKeyDropsAnUnusableServiceDate() {
        assertEquals(TripDetailsKey("T1"), tripDetailsKey("T1", null))
        assertEquals(TripDetailsKey("T1"), tripDetailsKey("T1", "1762646400000"))
        assertEquals(TripDetailsKey("T1"), tripDetailsKey("T1", "2026-11-09"))
        assertNull(tripDetailsKey("", "20261109"))
        assertNull(tripDetailsKey(null, "20261109"))
    }

    // ---- multi-stop trackers ----
    private val tracker = TransitTracker(
        id = "t-1", userId = "u-1", stopId = "BKK_F01000", stopName = "Nyugati pályaudvar M", routeId = "BKK_0040",
        routeShortName = "4", routeLongName = "Tram 4", routeType = "TRAM", routeColor = "FFD800", routeTextColor = "000000",
        headsign = "Újbuda-központ M", isFavorite = false, dedupeKey = "key-1", createdAt = "2026-01-01T00:00:00Z", stops = null,
    )

    @Test fun legacySingleStop() = assertEquals(
        listOf(TrackerStop("BKK_F01000", "Nyugati pályaudvar M", "Újbuda-központ M")),
        getTrackerStops(tracker),
    )

    @Test fun multiStopArray() {
        val stops = getTrackerStops(
            tracker.copy(
                headsign = "", isFavorite = true,
                stops = listOf(TrackerStop("BKK_F01000", "Nyugati pályaudvar M", ""), TrackerStop("BKK_F01001", "Oktogon M", "Széll Kálmán tér M")),
            ),
        )
        assertEquals(2, stops.size)
        assertEquals("Oktogon M", stops[1].stopName)
        assertEquals("Széll Kálmán tér M", stops[1].headsign)
    }

    @Test fun addsOrUpdatesStops() {
        val stops = listOf(TrackerStop("s1", "Stop 1", ""))
        val updated = addOrUpdateTrackerStop(stops, TrackerStop("s1", "Stop 1", "Direction A"))
        assertEquals(listOf(TrackerStop("s1", "Stop 1", "Direction A")), updated)
        val added = addOrUpdateTrackerStop(updated, TrackerStop("s2", "Stop 2", ""))
        assertEquals(2, added.size)
        assertEquals("s2", added[1].stopId)
        val added4 = addOrUpdateTrackerStop(addOrUpdateTrackerStop(added, TrackerStop("s3", "Stop 3", "")), TrackerStop("s4", "Stop 4", ""))
        assertEquals(4, added4.size)
        val added5 = addOrUpdateTrackerStop(added4, TrackerStop("s5", "Stop 5", ""))
        assertEquals(listOf("s1", "s2", "s3", "s4"), added5.map { it.stopId })
    }

    @Test fun removesStop() = assertEquals(
        listOf(TrackerStop("s2", "Stop 2", "")),
        removeTrackerStop(listOf(TrackerStop("s1", "Stop 1", ""), TrackerStop("s2", "Stop 2", "")), "s1"),
    )

    @Test fun stationIdsUnchanged() {
        assertEquals("BKK_CSF01330", normalizeStopIdToStation("BKK_CSF01330"))
        assertEquals("BKK_CS011732", normalizeStopIdToStation("BKK_CS011732"))
        assertEquals("AREA_CS778816_1", normalizeStopIdToStation("AREA_CS778816_1"))
    }

    @Test fun platformToCsf() {
        assertEquals("BKK_CSF01330", normalizeStopIdToStation("BKK_F01330"))
        assertEquals("BKK_CSF02072", normalizeStopIdToStation("BKK_F02072"))
    }

    @Test fun sixDigitToCs() {
        assertEquals("BKK_CS011732", normalizeStopIdToStation("BKK_011732"))
        assertEquals("BKK_CS009543", normalizeStopIdToStation("BKK_009543"))
    }

    @Test fun eightDigitHev() {
        assertEquals("BKK_09001", normalizeStopIdToStation("BKK_09001188"))
        assertEquals("BKK_19720", normalizeStopIdToStation("BKK_19720236"))
    }

    @Test fun fiveDigitHevUnchanged() {
        assertEquals("BKK_09001", normalizeStopIdToStation("BKK_09001"))
        assertEquals("BKK_09019", normalizeStopIdToStation("BKK_09019"))
        assertEquals("BKK_19720", normalizeStopIdToStation("BKK_19720"))
    }

    @Test fun emptyStopIds() {
        assertEquals("", normalizeStopIdToStation(""))
        assertEquals("", normalizeStopIdToStation(null))
    }

    @Test fun constants() {
        assertEquals(4, MaxTrackerStops)
        assertEquals(5, MaxFavoriteTrackers)
    }

    @Test fun countdown() {
        assertEquals("NOW", formatCountdown(0))
        assertEquals("NOW", formatCountdown(-3))
        assertEquals("3 MIN", formatCountdown(3))
        assertEquals("59 MIN", formatCountdown(59))
        assertEquals("60 MIN", formatCountdown(60))
        assertEquals("65 MIN", formatCountdown(65))
        assertEquals("120 MIN", formatCountdown(120))
    }

    @Test fun primeCountdown() {
        assertEquals("NOW", formatPrimeCountdown(0))
        assertEquals("NOW", formatPrimeCountdown(-4))
        assertEquals("2'", formatPrimeCountdown(2))
        assertEquals("73'", formatPrimeCountdown(73))
        assertEquals("120'", formatPrimeCountdown(120))
    }

    @Test fun compactCountdown() {
        assertEquals("NOW", formatCompactCountdown(0))
        assertEquals("NOW", formatCompactCountdown(-2))
        assertEquals("2'", formatCompactCountdown(2))
        assertEquals("59'", formatCompactCountdown(59))
        assertEquals("60'", formatCompactCountdown(60))
        assertEquals("78'", formatCompactCountdown(78))
        assertEquals("99'", formatCompactCountdown(CompactCountdownMaxMinutes))
        assertNull(formatCompactCountdown(100))
        assertNull(formatCompactCountdown(180))
    }

    @Test fun staleAfterBudget() {
        assertFalse(isArrivalsStale(now - ArrivalsStaleMs, now, false))
        assertTrue(isArrivalsStale(now - ArrivalsStaleMs - 1, now, false))
    }

    @Test fun noTimestampNeverStale() {
        assertFalse(isArrivalsStale(null, now, false))
        assertFalse(isArrivalsStale(0, now, false))
    }

    @Test fun offlineAlwaysStale() {
        assertTrue(isArrivalsStale(now, now, true))
        assertEquals(now, arrivalsStaleNow(now, now, true))
    }

    @Test fun freezesClockWhenStale() {
        val snapshot = now - ArrivalsStaleMs - 5_000
        assertEquals(snapshot, arrivalsStaleNow(snapshot, now, false))
        assertEquals(now, arrivalsStaleNow(now - 5_000, now, false))
    }
}
