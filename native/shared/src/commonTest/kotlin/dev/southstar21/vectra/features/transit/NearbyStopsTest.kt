package dev.southstar21.vectra.features.transit

import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lazy catalog getters are covered by CatalogDataTest with a mock engine.
 */
class NearbyStopsTest {
    private val catalog = listOf(
        CatalogStop("BKK_CSF01108", "Astoria", 47.494479, 19.060643),
        CatalogStop("BKK_CSF01116", "Blaha Lujza tér", 47.49665, 19.070596),
        CatalogStop("BKK_CSF00065", "Batthyány tér", 47.506377, 19.038676),
    )

    private val routeMap = mapOf(
        "BKK_5400" to CatalogRoute("BKK_5400", "M4", "Kelenföld vasútállomás M | Keleti pályaudvar M", "SUBWAY", "12A13C", "FFFFFF"),
        "BKK_3010" to CatalogRoute("BKK_3010", "1", "Kelenföld vasútállomás M | Bécsi út / Vörösvári út", "TRAM", "FFD800", "000000"),
    )

    private val now = 1_800_000_000_000L

    private fun makeArrivals(extraTrips: Map<String, FutarTripRef> = emptyMap(), extraTimes: List<FutarStopTime> = emptyList()): FutarArrivalsResponse {
        val soon = now / 1000 + 180
        val later = now / 1000 + 600
        val past = now / 1000 - 300
        return FutarArrivalsResponse(
            currentTime = now,
            status = "OK",
            data = FutarArrivalsData(
                entry = FutarArrivalsEntry(
                    stopId = "BKK_CSF01108",
                    stopTimes = listOf(
                        FutarStopTime(stopId = "BKK_F00073", stopHeadsign = "Keleti pályaudvar M", predictedArrivalTime = soon, tripId = "trip-m4-1"),
                        FutarStopTime(stopId = "BKK_CSF01108", stopHeadsign = "Kelenföld vasútállomás M", predictedArrivalTime = later, tripId = "trip-m4-2"),
                        FutarStopTime(stopId = "BKK_CSF01108", stopHeadsign = "Bécsi út / Vörösvári út", predictedArrivalTime = soon, tripId = "trip-tram-1"),
                        FutarStopTime(stopId = "BKK_CSF01108", stopHeadsign = "Bécsi út / Vörösvári út", predictedArrivalTime = past, tripId = "trip-tram-past"),
                        FutarStopTime(stopId = "BKK_CSF01108", stopHeadsign = "Cancelled", predictedArrivalTime = soon, tripId = "trip-cancelled", canceled = true),
                        FutarStopTime(stopId = "BKK_CSF01108", stopHeadsign = "No route ref", predictedArrivalTime = soon, tripId = "trip-no-route"),
                    ) + extraTimes,
                ),
                references = FutarReferences(
                    stops = mapOf(
                        "BKK_F00073" to FutarStop(id = "BKK_F00073", lat = 47.498321, lon = 19.071412, name = "Blaha Lujza tér", parentStationId = "BKK_CSF01108"),
                    ),
                    trips = mapOf(
                        "trip-m4-1" to FutarTripRef(routeId = "BKK_5400"),
                        "trip-m4-2" to FutarTripRef(routeId = "BKK_5400"),
                        "trip-tram-1" to FutarTripRef(routeId = "BKK_3010"),
                        "trip-tram-past" to FutarTripRef(routeId = "BKK_3010"),
                        "trip-cancelled" to FutarTripRef(routeId = "BKK_5400"),
                    ) + extraTrips,
                    routes = mapOf(
                        "BKK_9999" to FutarRoute(id = "BKK_9999", shortName = "X9", description = "Fallback route", type = "BUS", color = "009EE3", textColor = "FFFFFF"),
                    ),
                ),
            ),
        )
    }

    // ---- findNearbyStops ----
    @Test fun nearbySortedByDistance() {
        val stops = findNearbyStops(catalog, 47.494479, 19.060643, radiusMeters = 3000.0)
        assertEquals("BKK_CSF01108", stops[0].id)
        assertTrue(stops[0].distanceMeters < 10)
        assertEquals("BKK_CSF01116", stops[1].id)
        assertTrue(stops[1].distanceMeters > 700 && stops[1].distanceMeters < 900)
        assertEquals("BKK_CSF00065", stops[2].id)
    }

    @Test fun nearbyRespectsRadiusAndLimit() {
        assertEquals(listOf("BKK_CSF01108"), findNearbyStops(catalog, 47.494479, 19.060643, radiusMeters = 400.0).map { it.id })
        assertEquals(
            listOf("BKK_CSF01108", "BKK_CSF01116"),
            findNearbyStops(catalog, 47.494479, 19.060643, radiusMeters = 1000.0, limit = 2).map { it.id },
        )
    }

    // ---- isInHungary / resolveAnchor ----
    @Test fun acceptsHungarianCities() {
        assertTrue(isInHungary(47.4979, 19.0402))
        assertTrue(isInHungary(47.53, 21.63))
        assertTrue(isInHungary(47.68, 17.63))
        assertTrue(isInHungary(48.1, 20.79))
        assertTrue(isInHungary(46.07, 18.23))
        assertTrue(isInHungary(46.3, 20.1))
    }

    @Test fun rejectsForeignCities() {
        assertFalse(isInHungary(48.2082, 16.3738))
        assertFalse(isInHungary(48.1486, 17.1077))
        assertFalse(isInHungary(48.72, 21.26))
        assertFalse(isInHungary(46.17, 21.32))
        assertFalse(isInHungary(45.81, 15.98))
        assertFalse(isInHungary(46.2, 20.1))
        assertFalse(isInHungary(48.8566, 2.3522))
        assertFalse(isInHungary(40.7128, -74.006))
    }

    @Test fun anchorUsesUserInHungary() =
        assertEquals(MapAnchor(47.4979, 19.0402, MapAnchorLabel.You, false), resolveAnchor(47.4979, 19.0402))

    @Test fun anchorFallsBackToBudapest() = assertEquals(
        MapAnchor(BudapestCenter.lat, BudapestCenter.lon, MapAnchorLabel.BudapestCenter, true),
        resolveAnchor(48.2082, 16.3738),
    )

    // ---- parseNearbyBoards ----
    @Test fun boardsGroupAndSort() {
        val boards = parseNearbyBoards(makeArrivals(), routeMap, now)
        assertEquals(listOf("BKK_5400", "BKK_3010"), boards.map { it.routeId })
        val metro = boards[0]
        assertEquals("M4", metro.shortName)
        assertEquals("12A13C", metro.color)
        assertEquals(listOf("Keleti pályaudvar M", "Kelenföld vasútállomás M"), metro.arrivals.map { it.headSign })
        assertTrue(metro.arrivals[0].arrivalMs < metro.arrivals[1].arrivalMs)
        assertEquals("BKK_F00073", metro.arrivals[0].platformStopId)
        assertCloseTo(47.498321, metro.arrivals[0].platformLat, 5)
        assertCloseTo(19.071412, metro.arrivals[0].platformLon, 5)
        assertNull(metro.arrivals[1].platformStopId)
        assertNull(metro.arrivals[1].platformLat)
        assertNull(metro.arrivals[1].platformLon)
        val tram = boards[1]
        assertEquals(1, tram.arrivals.size)
        assertEquals("Bécsi út / Vörösvári út", tram.arrivals[0].headSign)
    }

    @Test fun boardsFallBackToReferencesThenRawId() {
        val data = makeArrivals(
            extraTrips = mapOf("trip-fallback" to FutarTripRef(routeId = "BKK_9999")),
            extraTimes = listOf(FutarStopTime(stopId = "BKK_CSF01108", stopHeadsign = "Fallback", predictedArrivalTime = now / 1000 + 240, tripId = "trip-fallback")),
        )
        val fallback = parseNearbyBoards(data, routeMap, now).find { it.routeId == "BKK_9999" }
        assertEquals("X9", fallback?.shortName)
        assertEquals("009EE3", fallback?.color)

        val m4 = parseNearbyBoards(makeArrivals(), null, now).find { it.routeId == "BKK_5400" }
        assertEquals("BKK_5400", m4?.shortName)
        assertEquals("BKK_5400", m4?.routeId)
    }

    @Test fun boardsEmptyForNull() = assertEquals(emptyList(), parseNearbyBoards(null, routeMap, now))

    // ---- filterDeparturesByMode ----
    private fun departure(routeType: String) = NearbyDeparture(
        "BKK_$routeType", routeType, routeType, "000000", "FFFFFF",
        listOf(Arrival(arrivalMs = 0, headSign = "Test", isPredicted = false, tripId = "$routeType-t")),
    )

    private val departures = listOf(departure("SUBWAY"), departure("TRAM"), departure("BUS"))

    @Test fun modeFilterNone() {
        assertEquals(3, filterDeparturesByMode(departures).size)
        assertEquals(3, filterDeparturesByMode(departures, "ALL").size)
        assertEquals(3, filterDeparturesByMode(departures, emptyList()).size)
    }

    @Test fun modeFilterLegacySingle() = assertEquals(listOf("TRAM"), filterDeparturesByMode(departures, "TRAM").map { it.routeType })

    @Test fun modeFilterAny() =
        assertEquals(listOf("SUBWAY", "BUS"), filterDeparturesByMode(departures, listOf("SUBWAY", "BUS")).map { it.routeType })

    @Test fun modeFilterCaseInsensitiveIgnoresAll() =
        assertEquals(listOf("TRAM"), filterDeparturesByMode(departures, listOf("tram", "ALL")).map { it.routeType })

    @Test fun modeFilterUnknown() = assertEquals(emptyList(), filterDeparturesByMode(departures, listOf("FERRY")))

    @Test fun formatsDistance() {
        assertEquals("0 m", formatDistanceMeters(0.0))
        assertEquals("350 m", formatDistanceMeters(350.0))
        assertEquals("1.2 km", formatDistanceMeters(1200.0))
    }

    // ---- radar ----
    @Test fun walkMinutes() {
        assertEquals(1, calculateWalkMinutes(0.0))
        assertEquals(1, calculateWalkMinutes(70.0))
        assertEquals(2, calculateWalkMinutes(144.0))
        assertEquals(7, calculateWalkMinutes(500.0))
    }

    @Test fun feasibility() {
        assertEquals(Feasibility.Catchable, calculateFeasibility(5, 2))
        assertEquals(Feasibility.Hurry, calculateFeasibility(4, 3))
        assertEquals(Feasibility.Hurry, calculateFeasibility(4, 4))
        assertEquals(Feasibility.Missed, calculateFeasibility(3, 5))
    }

    private val radarNow = 1_000_000L
    private val deak = NearbyStop("BKK_F01", "Deák tér", 47.4979, 19.054, 140.0)
    private fun arr(tripId: String, mins: Int, headSign: String, platform: Triple<String, Double, Double>? = null) = Arrival(
        arrivalMs = radarNow + mins * 60_000L, headSign = headSign, isPredicted = true, tripId = tripId,
        platformStopId = platform?.first, platformLat = platform?.second, platformLon = platform?.third,
    )
    private fun board(routeId: String, shortName: String, type: String, vararg arrivals: Arrival) =
        NearbyDeparture(routeId, shortName, type, "000000", "FFFFFF", arrivals.toList())

    @Test fun radarBuildsAndRanks() {
        val stops = listOf(deak, NearbyStop("BKK_F02", "Astoria", 47.494, 19.06, 400.0))
        val map = mapOf(
            "BKK_F01" to listOf(board("BKK_M3", "M3", "SUBWAY", arr("t1", 4, "Újpest", Triple("BKK_F01P", 47.4981, 19.0543)))),
            "BKK_F02" to listOf(board("BKK_47", "47", "TRAM", arr("t2", 6, "Városház tér"))),
        )
        val radar = buildRadarDepartures(stops, map, radarNow)
        assertEquals(2, radar.size)
        assertEquals("47", radar[0].shortName)
        assertEquals("t2", radar[0].tripId)
        assertEquals("M3", radar[1].shortName)
        assertEquals("t1", radar[1].tripId)
        assertEquals(Feasibility.Catchable, radar[1].feasibility)
        assertEquals(2, radar[1].walkMinutes)
        assertEquals("BKK_F01P", radar[1].platformStopId)
        assertCloseTo(47.4981, radar[1].platformLat, 4)
        assertCloseTo(19.0543, radar[1].platformLon, 4)
        assertNull(radar[0].platformLat)
    }

    @Test fun radarDedupesPerPlatformTrip() {
        val map = mapOf("BKK_F01" to listOf(board("BKK_M3", "M3", "SUBWAY", arr("t1", 4, "Újpest"), arr("t1", 4, "Újpest"))))
        val radar = buildRadarDepartures(listOf(deak), map, radarNow)
        assertEquals(1, radar.size)
        assertEquals("BKK_F01-BKK_M3-t1-1240000", radar[0].id)
    }

    @Test fun radarKeepsSameTripAtDifferentTimes() {
        val map = mapOf("BKK_F01" to listOf(board("BKK_M3", "M3", "SUBWAY", arr("t1", 4, "Újpest"), arr("t1", 30, "Újpest"))))
        val radar = buildRadarDepartures(listOf(deak), map, radarNow)
        assertEquals(2, radar.size)
        assertEquals(listOf(radarNow + 4 * 60_000, radarNow + 30 * 60_000), radar.map { it.arrivalMs })
        assertEquals(2, radar.map { it.id }.toSet().size)
    }

    @Test fun radarFiltersByMode() {
        val map = mapOf(
            "BKK_F01" to listOf(
                board("BKK_M3", "M3", "SUBWAY", arr("t1", 4, "Újpest")),
                board("BKK_47", "47", "TRAM", arr("t2", 3, "Városház tér")),
            ),
        )
        val onlyMetro = buildRadarDepartures(listOf(deak), map, radarNow, modeFilter = listOf("SUBWAY"))
        assertEquals(listOf("M3"), onlyMetro.map { it.shortName })
        val onlyTram = buildRadarDepartures(listOf(deak), map, radarNow, modeFilter = listOf("TRAM"))
        assertEquals(listOf("47"), onlyTram.map { it.shortName })
    }

    @Test fun radarFiltersBySearch() {
        val map = mapOf(
            "BKK_F01" to listOf(
                board("BKK_M3", "M3", "SUBWAY", arr("t1", 4, "Újpest-központ")),
                board("BKK_47", "47", "TRAM", arr("t2", 3, "Városház tér")),
            ),
        )
        assertEquals(listOf("M3"), buildRadarDepartures(listOf(deak), map, radarNow, searchQuery = "m3").map { it.shortName })
        assertEquals(listOf("47"), buildRadarDepartures(listOf(deak), map, radarNow, searchQuery = "városház").map { it.shortName })
    }

    @Test fun radarIsolatesM4() {
        val stop = NearbyStop("BKK_F01", "Kálvin tér M", 47.489, 19.061, 200.0)
        val map = mapOf(
            "BKK_F01" to listOf(
                board("BKK_M4", "M4", "SUBWAY", arr("t-m4", 3, "Keleti pályaudvar M")),
                board("BKK_M3", "M3", "SUBWAY", arr("t-m3", 2, "Újpest (átszállás M4)")),
                board("BKK_4", "4", "TRAM", arr("t-tram4", 4, "Újbuda-központ")),
                board("BKK_47", "47", "TRAM", arr("t-tram47", 5, "Városház tér")),
                board("BKK_7", "7", "BUS", arr("t-bus7", 2, "Kelenföld vasútállomás M (M4)")),
            ),
        )
        val result = buildRadarDepartures(listOf(stop), map, radarNow, searchQuery = "M4")
        assertEquals(1, result.size)
        assertEquals("M4", result[0].shortName)
        assertEquals("BKK_M4", result[0].routeId)
    }

    @Test fun radarModeArrays() {
        val now = 1_700_000_000_000L
        val stops = listOf(NearbyStop("s1", "Stop", 47.49, 19.05, 100.0))
        val boards = listOf(
            NearbyDeparture("r-tram", "4", "TRAM", "F7C600", "000000", listOf(Arrival(now + 120_000, "A", isPredicted = true, tripId = "t1"))),
            NearbyDeparture("r-bus", "7", "BUS", "0072BC", "FFFFFF", listOf(Arrival(now + 300_000, "B", isPredicted = false, tripId = "t2"))),
            NearbyDeparture("r-night-bus", "907", "BUS", "000000", "FFFFFF", listOf(Arrival(now + 180_000, "C", isPredicted = true, tripId = "t3"))),
        )
        val items = buildRadarDepartures(stops, mapOf("s1" to boards), now, modeFilter = listOf("TRAM"), maxItems = 10)
        assertEquals(listOf("r-tram"), items.map { it.routeId })
    }

    @Test fun radarSortsRoutesNumerically() {
        val map = mapOf(
            "BKK_F01" to listOf(
                board("r100", "100E", "BUS", arr("a", 2, "X")),
                board("r9", "9", "BUS", arr("b", 3, "Y")),
                board("r28a", "28A", "BUS", arr("c", 4, "Z")),
            ),
        )
        assertEquals(listOf("9", "28A", "100E"), buildRadarDepartures(listOf(deak), map, radarNow).map { it.shortName })
    }

    // ---- matchesLineSearch ----
    @Test fun lineSearchStrictM4() {
        assertTrue(matchesLineSearch("M4", "Keleti pályaudvar M", "Kálvin tér", "M4"))
        assertTrue(matchesLineSearch("M4", "Keleti pályaudvar M", "Kálvin tér", "m4"))
        assertTrue(matchesLineSearch("M4", "Keleti pályaudvar M", "Kálvin tér", "M 4"))
        assertTrue(matchesLineSearch("M4", "Keleti pályaudvar M", "Kálvin tér", "metro 4"))
        assertFalse(matchesLineSearch("M3", "Újpest (átszállás M4)", "Deák Ferenc tér", "M4"))
        assertFalse(matchesLineSearch("4", "Újbuda-központ", "Kálvin tér M4", "M4"))
        assertFalse(matchesLineSearch("47", "Városház tér", "Fővám tér M4", "M4"))
        assertFalse(matchesLineSearch("7", "Kelenföld M (átszállás M4)", "Keleti M4", "M4"))
    }

    @Test fun lineSearchStrictDigits() {
        assertTrue(matchesLineSearch("4", "Újbuda-központ", "Margit híd", "4"))
        assertFalse(matchesLineSearch("14", "Lehel tér", "Káposztásmegyer", "4"))
        assertFalse(matchesLineSearch("24", "Keleti pályaudvar", "Közvágóhíd", "4"))
        assertFalse(matchesLineSearch("47", "Deák tér", "Városház tér", "4"))
        assertFalse(matchesLineSearch("4-6", "Széll Kálmán tér", "Nyugati pályaudvar", "4"))
        assertTrue(matchesLineSearch("4-6", "Széll Kálmán tér", "Nyugati pályaudvar", "4-6"))
        assertTrue(matchesLineSearch("4-6", "Széll Kálmán tér", "Nyugati pályaudvar", "4/6"))
        assertTrue(matchesLineSearch("4-6", "Széll Kálmán tér", "Nyugati pályaudvar", "4 6"))
        assertTrue(matchesLineSearch("2B", "Jászai Mari tér", "Pesterzsébet", "2B"))
        assertTrue(matchesLineSearch("2B", "Jászai Mari tér", "Pesterzsébet", "2b"))
    }

    @Test fun lineSearchTextual() {
        assertTrue(matchesLineSearch("7", "Keleti pályaudvar M", "Astoria", "keleti"))
        assertTrue(matchesLineSearch("47", "Városház tér", "Deák Ferenc tér", "deak"))
        assertTrue(matchesLineSearch("47", "Városház tér", "Deák Ferenc tér", "varoshaz"))
        assertTrue(matchesLineSearch("7", "Újpalota", "Blaha Lujza tér M", "blaha"))
    }

    // ---- findStopsInBounds ----
    private val boxStops = listOf(
        CatalogStop("a", "North", 47.5, 19.05),
        CatalogStop("b", "South", 47.485, 19.05),
        CatalogStop("c", "Outside east", 47.5, 19.2),
        CatalogStop("d", "Outside south", 47.4, 19.05),
    )
    private val box = LatLonBounds(north = 47.51, south = 47.47, east = 19.06, west = 19.04)

    @Test fun inBoundsNearestFirst() {
        val r = findStopsInBounds(boxStops, box)
        assertEquals(listOf("b", "a"), r.map { it.id })
        assertTrue(r[0].distanceMeters > 0)
    }

    @Test fun inBoundsLimit() = assertEquals(listOf("b"), findStopsInBounds(boxStops, box, limit = 1).map { it.id })

    @Test fun inBoundsEmpty() = assertEquals(emptyList(), findStopsInBounds(emptyList(), box))

    // ---- padBoundsToMinSpan ----
    private val mpd = 111_320.0

    @Test fun padsTinyBoundsSymmetrically() {
        val padded = padBoundsToMinSpan(LatLonBounds(47.5001, 47.4999, 19.0501, 19.0499), 500.0)
        val cLat = (padded.north + padded.south) / 2
        val cLon = (padded.east + padded.west) / 2
        assertCloseTo(47.5, cLat, 10)
        assertCloseTo(19.05, cLon, 10)
        assertCloseTo(500.0, (padded.north - padded.south) * mpd, 3)
        assertCloseTo(500.0, (padded.east - padded.west) * mpd * cos(cLat * PI / 180), 3)
    }

    @Test fun leavesLargeBounds() {
        val b = LatLonBounds(47.51, 47.48, 19.08, 19.03)
        assertEquals(b, padBoundsToMinSpan(b, 500.0))
    }

    @Test fun expandsOnlyShortAxis() {
        val padded = padBoundsToMinSpan(LatLonBounds(47.505, 47.495, 19.0501, 19.0499), 500.0)
        assertCloseTo(47.505, padded.north, 10)
        assertCloseTo(47.495, padded.south, 10)
        assertCloseTo(500.0, (padded.east - padded.west) * mpd * cos((padded.north + padded.south) / 2 * PI / 180), 3)
    }

    @Test fun invalidPadInputUnchanged() {
        val invalid = LatLonBounds(Double.NaN, 47.5, 19.05, 19.04)
        assertEquals(invalid, padBoundsToMinSpan(invalid, 500.0))
        val b = LatLonBounds(47.5001, 47.4999, 19.0501, 19.0499)
        assertEquals(b, padBoundsToMinSpan(b, 0.0))
        assertEquals(b, padBoundsToMinSpan(b, -500.0))
        assertEquals(b, padBoundsToMinSpan(b, Double.NaN))
    }

    @Test fun padStaysFiniteAtPole() {
        val padded = padBoundsToMinSpan(LatLonBounds(90.0001, 89.9999, 19.0001, 18.9999), 500.0)
        assertTrue(padded.east.isFinite())
        assertTrue(padded.west.isFinite())
    }

    // ---- viewportFromUncoveredRect ----
    @Test fun viewportPadsTinyRect() {
        val v = viewportFromUncoveredRect(UnprojectedPoint(47.5001, 19.0499), UnprojectedPoint(47.4999, 19.0501), UnprojectedPoint(47.5, 19.05), 19.0)
        assertCloseTo(500.0, (v.bounds.north - v.bounds.south) * 111_320, 3)
    }

    @Test fun viewportFromCorners() = assertEquals(
        VisibleViewport(47.495, 19.055, 15.5, LatLonBounds(47.51, 47.48, 19.08, 19.03)),
        viewportFromUncoveredRect(UnprojectedPoint(47.51, 19.03), UnprojectedPoint(47.48, 19.08), UnprojectedPoint(47.495, 19.055), 15.5),
    )

    @Test fun viewportLowerArea() {
        val v = viewportFromUncoveredRect(UnprojectedPoint(47.51, 19.03), UnprojectedPoint(47.45, 19.08), UnprojectedPoint(47.495, 19.055), 15.5)
        assertEquals(47.45, v.bounds.south)
        assertEquals(47.495, v.lat)
    }

    @Test fun viewportNormalizesCorners() = assertEquals(
        LatLonBounds(47.51, 47.48, 19.08, 19.03),
        viewportFromUncoveredRect(UnprojectedPoint(47.48, 19.08), UnprojectedPoint(47.51, 19.03), UnprojectedPoint(47.49, 19.06), 12.0).bounds,
    )

    // ---- clampBottomInset ----
    @Test fun clampsInset() {
        assertEquals(0.0, clampBottomInset(0.0, 800.0))
        assertEquals(300.0, clampBottomInset(300.0, 800.0))
        assertEquals(640.0, clampBottomInset(640.0, 800.0))
        assertEquals(640.0, clampBottomInset(999.0, 800.0))
    }

    @Test fun clampsDegenerateInset() {
        assertEquals(0.0, clampBottomInset(-50.0, 800.0))
        assertEquals(0.0, clampBottomInset(Double.NaN, 800.0))
        assertEquals(0.0, clampBottomInset(Double.POSITIVE_INFINITY, 800.0))
        assertEquals(0.0, clampBottomInset(100.0, 0.0))
    }

    // ---- buildExplorerMapStops ----
    private val explorerBounds = LatLonBounds(north = 47.51, south = 47.48, east = 19.08, west = 19.05)
    private val blaha = NearbyStop("BKK_CSF01116", "Blaha Lujza tér", 47.49665, 19.070596, 40.0)
    private val astoria = NearbyStop("BKK_CSF01108", "Astoria", 47.494479, 19.060643, 300.0)
    private fun explorerArrivals(stops: Map<String, FutarStop>) = FutarArrivalsResponse(
        currentTime = now, status = "OK",
        data = FutarArrivalsData(entry = FutarArrivalsEntry(stopId = blaha.id), references = FutarReferences(stops = stops)),
    )

    @Test fun explorerReplacesStationWithPlatforms() {
        val refs = linkedMapOf(
            "BKK_CSF01116" to FutarStop(id = "BKK_CSF01116", lat = blaha.lat, lon = blaha.lon, name = "Blaha Lujza tér"),
            "BKK_F01166" to FutarStop(id = "BKK_F01166", lat = 47.49665, lon = 19.070696, name = "Blaha Lujza tér M"),
            "BKK_F01168" to FutarStop(id = "BKK_F01168", lat = 47.4969, lon = 19.0707, name = "Blaha Lujza tér M (Népszínház utca)"),
        )
        val mapStops = buildExplorerMapStops(listOf(blaha, astoria), mapOf(blaha.id to explorerArrivals(refs)), explorerBounds)
        assertEquals(listOf("BKK_F01166", "BKK_F01168", "BKK_CSF01108"), mapStops.map { it.id })
        assertEquals("Blaha Lujza tér M", mapStops[0].name)
        assertEquals("BKK_CSF01116", mapStops[0].stationId)
        assertEquals(true, mapStops[0].isPlatform)
        assertEquals("BKK_CSF01116", mapStops[1].stationId)
        assertEquals(true, mapStops[1].isPlatform)
        assertNull(mapStops[2].stationId)
        assertNull(mapStops[2].isPlatform)
    }

    @Test fun explorerDedupesPlatforms() {
        val refs = linkedMapOf(
            "alias1" to FutarStop(id = "BKK_F1", lat = 47.4968, lon = 19.0708, name = "A"),
            "alias2" to FutarStop(id = "BKK_F1", lat = 47.497, lon = 19.071, name = "A duplicate id"),
            "BKK_F2" to FutarStop(id = "BKK_F2", lat = 47.49681, lon = 19.070801, name = "A stacked twin"),
            "BKK_F3" to FutarStop(id = "BKK_F3", lat = 47.4975, lon = 19.0725, name = "C"),
            "BKK_F0" to FutarStop(id = "BKK_F0", lat = 0.0, lon = 0.0, name = "Zero"),
            "BKK_FNAN" to FutarStop(id = "BKK_FNAN", lat = Double.NaN, lon = 19.07, name = "NaN"),
            "BKK_FAR" to FutarStop(id = "BKK_FAR", lat = 47.9, lon = 19.9, name = "Out of bounds"),
        )
        val mapStops = buildExplorerMapStops(listOf(blaha), mapOf(blaha.id to explorerArrivals(refs)), explorerBounds)
        assertEquals(listOf("BKK_F1", "BKK_F3"), mapStops.map { it.id })
    }

    @Test fun explorerKeepsCatalogDotWithoutOtherPlatforms() {
        val stationOnly = mapOf("BKK_CSF01116" to FutarStop(id = "BKK_CSF01116", lat = blaha.lat, lon = blaha.lon, name = "Blaha Lujza tér"))
        assertEquals(listOf(blaha.toExplorerMapStop()), buildExplorerMapStops(listOf(blaha), mapOf(blaha.id to explorerArrivals(stationOnly)), explorerBounds))
        assertEquals(listOf(blaha.toExplorerMapStop()), buildExplorerMapStops(listOf(blaha), emptyMap(), explorerBounds))
    }

    @Test fun explorerKeepsCatalogDotWhenPlatformsOffscreen() {
        val off = mapOf("BKK_FAR" to FutarStop(id = "BKK_FAR", lat = 47.9, lon = 19.9, name = "Far away"))
        assertEquals(listOf(blaha.toExplorerMapStop()), buildExplorerMapStops(listOf(blaha), mapOf(blaha.id to explorerArrivals(off)), explorerBounds))
    }

    @Test fun nearbyCountdown() {
        assertEquals("NOW", formatNearbyCountdown(0))
        assertEquals("5'", formatNearbyCountdown(5))
        assertNotNull(routeNameCompare("a", "b"))
    }
}
