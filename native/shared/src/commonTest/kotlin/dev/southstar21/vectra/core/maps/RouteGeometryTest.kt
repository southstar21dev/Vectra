package dev.southstar21.vectra.core.maps

import dev.southstar21.vectra.core.util.roundHalfUp
import dev.southstar21.vectra.core.util.toFixed
import dev.southstar21.vectra.features.transit.FutarPolyline
import dev.southstar21.vectra.features.transit.FutarRouteVariant
import dev.southstar21.vectra.features.transit.FutarStop
import dev.southstar21.vectra.features.transit.FutarTripDetailsData
import dev.southstar21.vectra.features.transit.FutarTripDetailsEntry
import dev.southstar21.vectra.features.transit.FutarTripDetailsResponse
import dev.southstar21.vectra.features.transit.FutarTripStopTime
import dev.southstar21.vectra.features.transit.ResolvedStopRef
import dev.southstar21.vectra.features.transit.assertCloseTo
import dev.southstar21.vectra.features.transit.resolveStopRefs
import dev.southstar21.vectra.features.transit.selectTripPolyline
import dev.southstar21.vectra.features.transit.selectTripStopIds
import dev.southstar21.vectra.features.transit.selectVariantPolylines
import dev.southstar21.vectra.features.transit.selectVariantStopIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteGeometryTest {
    private fun ll(lat: Double, lon: Double) = LatLon(lat, lon)

    // ---- decodePolyline ----
    @Test fun decodesTheCanonicalGoogleExample() {
        assertEquals(listOf(ll(38.5, -120.2), ll(40.7, -120.95), ll(43.252, -126.453)), decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@"))
    }

    @Test fun decodesASinglePoint() {
        assertEquals(listOf(ll(38.5, -120.2)), decodePolyline("_p~iF~ps|U"))
    }

    // ---- makeProjector ----
    @Test fun projectorMapsLonToXAndKeepsNorthUp() {
        val project = makeProjector(listOf(ll(47.4, 19.0), ll(47.6, 19.2)), 100.0, 100.0)
        val sw = project(ll(47.4, 19.0))
        val ne = project(ll(47.6, 19.2))
        assertTrue(ne.x > sw.x)
        assertTrue(ne.y < sw.y)
    }

    @Test fun projectorCentersASinglePoint() {
        val p = makeProjector(listOf(ll(47.5, 19.1)), 200.0, 100.0)(ll(47.5, 19.1))
        assertEquals(100.0, p.x)
        assertEquals(50.0, p.y)
    }

    @Test fun projectorAppliesUniformPadding() {
        val project = makeProjector(listOf(ll(47.4, 19.0), ll(47.6, 19.2)), 100.0, 100.0)
        val sw = project(ll(47.4, 19.0))
        val ne = project(ll(47.6, 19.2))
        assertTrue(sw.x >= 8 - 1e-9)
        assertTrue(sw.y <= 92 + 1e-9)
        assertTrue(ne.x <= 92 + 1e-9)
        assertTrue(ne.y >= 8 - 1e-9)
    }

    // ---- projectVehicleOntoPolyline ----
    @Test fun projectsOntoNearestSegment() {
        val line = listOf(Point(0.0, 0.0), Point(10.0, 0.0))
        val r = assertNotNull(projectVehicleOntoPolyline(Point(5.0, 4.0), line))
        assertEquals(Point(5.0, 0.0), r.point)
        assertEquals(5.0, r.distance)
    }

    @Test fun projectionClampsBeyondEnds() {
        val line = listOf(Point(0.0, 0.0), Point(10.0, 0.0))
        assertEquals(Point(10.0, 0.0), projectVehicleOntoPolyline(Point(20.0, 0.0), line)!!.point)
        assertEquals(Point(0.0, 0.0), projectVehicleOntoPolyline(Point(-5.0, 0.0), line)!!.point)
    }

    @Test fun projectionNullForEmpty() {
        assertNull(projectVehicleOntoPolyline(Point(0.0, 0.0), emptyList()))
    }

    @Test fun cumulativeDistancesAccumulate() {
        assertEquals(
            listOf(0.0, 5.0, 5.0, 15.0),
            cumulativeDistances(listOf(Point(0.0, 0.0), Point(3.0, 4.0), Point(3.0, 4.0), Point(13.0, 4.0))),
        )
    }

    @Test fun nextStopIndexFirstNotReached() {
        assertEquals(2, nextStopIndex(listOf(0.0, 5.0, 10.0, 15.0), 6.0))
    }

    @Test fun nextStopIndexClampsToLast() {
        assertEquals(2, nextStopIndex(listOf(0.0, 5.0, 10.0), 99.0))
    }

    // ---- pointAtFraction ----
    private val fracLine = listOf(ll(47.5, 19.04), ll(47.5, 19.05), ll(47.5, 19.06))

    @Test fun pointAtFractionNullForEmpty() = assertNull(pointAtFraction(emptyList(), 0.5))

    @Test fun pointAtFractionSinglePoint() = assertEquals(fracLine[0], pointAtFraction(listOf(fracLine[0]), 0.5))

    @Test fun pointAtFractionEnds() {
        assertEquals(fracLine[0], pointAtFraction(fracLine, 0.0))
        assertEquals(fracLine.last(), pointAtFraction(fracLine, 1.0))
    }

    @Test fun pointAtFractionClamps() {
        assertEquals(fracLine[0], pointAtFraction(fracLine, -1.0))
        assertEquals(fracLine.last(), pointAtFraction(fracLine, 2.0))
    }

    @Test fun pointAtFractionInterpolatesMidpoint() {
        val mid = assertNotNull(pointAtFraction(fracLine, 0.5))
        assertCloseTo(19.05, mid.lon, 5)
        assertCloseTo(47.5, mid.lat, 5)
    }

    // ---- snapToPolyline ----
    private val eastLine = listOf(ll(47.5, 19.0), ll(47.5, 19.01))

    @Test fun snapKeepsOnLinePoint() {
        val r = assertNotNull(snapToPolyline(ll(47.5, 19.005), eastLine))
        assertCloseTo(47.5, r.point.lat, 5)
        assertCloseTo(19.005, r.point.lon, 5)
    }

    @Test fun snapProjectsOffLinePoint() {
        val r = snapToPolyline(ll(47.501, 19.004), eastLine)!!
        assertCloseTo(47.5, r.point.lat, 5)
        assertCloseTo(19.004, r.point.lon, 5)
    }

    @Test fun snapClampsBeyondEnds() {
        assertEquals(eastLine.last(), snapToPolyline(ll(47.5, 19.05), eastLine)!!.point)
        assertEquals(eastLine[0], snapToPolyline(ll(47.5, 18.9), eastLine)!!.point)
    }

    @Test fun snapNullForEmptyAndPointForSingle() {
        assertNull(snapToPolyline(ll(1.0, 2.0), emptyList()))
        assertEquals(SnappedPoint(ll(1.0, 2.0), 0.0), snapToPolyline(ll(1.0, 2.0), listOf(ll(1.0, 2.0))))
    }

    @Test fun snapAccumulatesDistance() {
        val line = listOf(ll(47.5, 19.0), ll(47.5, 19.01), ll(47.51, 19.01))
        val r = snapToPolyline(ll(47.5025, 19.01), line)!!
        val east = haversineMeters(line[0], line[1])
        assertCloseTo(east + 0.25 * haversineMeters(line[1], line[2]), r.distance, 1)
        assertCloseTo(47.5025, r.point.lat, 5)
        assertCloseTo(19.01, r.point.lon, 5)
    }

    // At 47.5°N a degree of longitude is 0.68 of a degree of latitude. The point is 0.004° of latitude
    // (445 m) south of the northbound leg and 0.005° of longitude (376 m) west of it, so in metres it
    // lies nearer the northbound leg; in raw degrees it looked nearer the eastbound one.
    @Test fun snapPicksTheNearerSegmentInMetres() {
        val line = listOf(ll(47.5, 19.0), ll(47.5, 19.02), ll(47.52, 19.02))
        val r = assertNotNull(snapToPolyline(ll(47.504, 19.015), line))
        assertCloseTo(19.02, r.point.lon, 6)
        assertCloseTo(47.504, r.point.lat, 6)
    }

    // The perpendicular foot on a diagonal segment, checked against a local metric frame.
    @Test fun snapFootIsPerpendicularInMetres() {
        val a = ll(47.5, 19.0)
        val b = ll(47.51, 19.01)
        val p = ll(47.5075, 19.0025)
        val r = assertNotNull(snapToPolyline(p, listOf(a, b)))
        val k = kotlin.math.cos(p.lat * kotlin.math.PI / 180)
        val (bx, by) = (b.lon - a.lon) * k to (b.lat - a.lat)
        val (px, py) = (p.lon - a.lon) * k to (p.lat - a.lat)
        val t = (px * bx + py * by) / (bx * bx + by * by)
        assertCloseTo(a.lat + t * (b.lat - a.lat), r.point.lat, 7)
        assertCloseTo(a.lon + t * (b.lon - a.lon), r.point.lon, 7)
        assertCloseTo(haversineMeters(a, r.point), r.distance, 3)
    }

    // A square 0.01° × 0.01° near Budapest is ~0.68 as wide as it is tall on the ground.
    @Test fun projectorKeepsGroundProportions() {
        val project = makeProjector(listOf(ll(47.5, 19.0), ll(47.51, 19.01)), 100.0, 100.0)
        val sw = project(ll(47.5, 19.0))
        val ne = project(ll(47.51, 19.01))
        assertCloseTo(kotlin.math.cos(47.505 * kotlin.math.PI / 180), (ne.x - sw.x) / (sw.y - ne.y), 4)
    }

    // ---- cumulativeHaversineDistances + pointAtDistance ----
    private val lLine = listOf(ll(47.5, 19.0), ll(47.5, 19.01), ll(47.51, 19.01))

    @Test fun haversineDistancesAccumulate() {
        val d = cumulativeHaversineDistances(lLine)
        assertEquals(0.0, d[0])
        assertCloseTo(haversineMeters(lLine[0], lLine[1]), d[1], 1)
        assertCloseTo(d[1] + haversineMeters(lLine[1], lLine[2]), d[2], 1)
    }

    @Test fun pointAtDistanceInterpolates() {
        val d = cumulativeHaversineDistances(lLine)
        val pt = pointAtDistance(lLine, d, d[1] / 2)
        assertCloseTo(47.5, pt.lat, 5)
        assertCloseTo(19.005, pt.lon, 5)
    }

    @Test fun pointAtDistanceSecondLeg() {
        val d = cumulativeHaversineDistances(lLine)
        val pt = pointAtDistance(lLine, d, d[1] + 0.5 * (d[2] - d[1]))
        assertCloseTo(47.505, pt.lat, 5)
        assertCloseTo(19.01, pt.lon, 5)
    }

    @Test fun pointAtDistanceClamps() {
        val d = cumulativeHaversineDistances(lLine)
        assertEquals(lLine[0], pointAtDistance(lLine, d, -100.0))
        assertEquals(lLine.last(), pointAtDistance(lLine, d, 1e9))
    }

    // ---- selectVariantPolylines ----
    private val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
    private val points = listOf(ll(38.5, -120.2), ll(40.7, -120.95), ll(43.252, -126.453))
    private fun variant(headsign: String, pts: String? = encoded) = FutarRouteVariant(headsign = headsign, polyline = FutarPolyline(points = pts))

    @Test fun polylinesExactHeadsign() = assertEquals(listOf(points), selectVariantPolylines(listOf(variant("A"), variant("B")), "B"))

    @Test fun polylinesFuzzyHeadsign() = assertEquals(
        listOf(points),
        selectVariantPolylines(listOf(variant("Örs vezér tere"), variant("Széll Kálmán tér M")), "ors  vezer   tere"),
    )

    @Test fun polylinesFallbackToAll() {
        val v = listOf(variant("A"), variant("B"))
        assertEquals(listOf(points, points), selectVariantPolylines(v, "Unknown"))
        assertEquals(listOf(points, points), selectVariantPolylines(v, null))
    }

    @Test fun polylinesSkipShort() =
        assertEquals(listOf(points), selectVariantPolylines(listOf(variant("A"), variant("B", "_p~iF~ps|U")), "A"))

    @Test fun polylinesRestrictToMemberRoute() {
        val v = listOf(
            FutarRouteVariant(headsign = "Móricz Zsigmond körtér M", routeId = "BKK_4", polyline = FutarPolyline(encoded)),
            FutarRouteVariant(headsign = "Móricz Zsigmond körtér M", routeId = "BKK_6", polyline = FutarPolyline("_p~iF~ps|U_ulLnnqC")),
        )
        assertEquals(listOf(listOf(ll(38.5, -120.2), ll(40.7, -120.95))), selectVariantPolylines(v, "Móricz Zsigmond körtér M", "BKK_6"))
    }

    @Test fun polylinesIgnoreUntaggedRouteId() {
        val v = listOf(variant("A"), variant("B"))
        assertEquals(listOf(points), selectVariantPolylines(v, "A", "BKK_99"))
        assertEquals(listOf(points, points), selectVariantPolylines(v, null, "BKK_99"))
    }

    @Test fun polylinesHeadsignOnlyWithoutRouteId() =
        assertEquals(listOf(points), selectVariantPolylines(listOf(variant("A"), variant("B")), "B"))

    // ---- selectVariantStopIds ----
    private fun sv(headsign: String, ids: List<String>) = FutarRouteVariant(headsign = headsign, stopIds = ids)

    @Test fun stopIdsExact() = assertEquals(listOf("b1"), selectVariantStopIds(listOf(sv("A", listOf("a1", "a2")), sv("B", listOf("b1"))), "B"))

    @Test fun stopIdsFuzzy() = assertEquals(
        listOf("x1"),
        selectVariantStopIds(listOf(sv("Örs vezér tere", listOf("x1")), sv("Széll Kálmán tér M", listOf("y1"))), "ors  vezer   tere"),
    )

    @Test fun stopIdsFallback() {
        val v = listOf(sv("A", listOf("a1")), sv("B", listOf("b1")))
        assertEquals(listOf("a1", "b1"), selectVariantStopIds(v, "Unknown"))
        assertEquals(listOf("a1", "b1"), selectVariantStopIds(v, null))
    }

    @Test fun stopIdsDedupe() = assertEquals(
        listOf("s1", "s2", "s3"),
        selectVariantStopIds(listOf(sv("A", listOf("s1", "s2")), sv("A", listOf("s2", "s3", "s1"))), "A"),
    )

    @Test fun stopIdsEmpty() {
        assertEquals(emptyList(), selectVariantStopIds(emptyList(), "A"))
        assertEquals(emptyList(), selectVariantStopIds(listOf(sv("A", emptyList())), "A"))
    }

    @Test fun stopIdsMemberRoute() {
        val v = listOf(
            FutarRouteVariant(headsign = "Móricz Zsigmond körtér M", routeId = "BKK_4", stopIds = listOf("a1", "a2")),
            FutarRouteVariant(headsign = "Móricz Zsigmond körtér M", routeId = "BKK_6", stopIds = listOf("b1")),
        )
        assertEquals(listOf("b1"), selectVariantStopIds(v, "Móricz Zsigmond körtér M", "BKK_6"))
    }

    @Test fun stopIdsIgnoreUnknownRouteId() {
        val v = listOf(
            FutarRouteVariant(headsign = "A", routeId = "BKK_4", stopIds = listOf("a1")),
            FutarRouteVariant(headsign = "A", routeId = "BKK_6", stopIds = listOf("b1")),
        )
        assertEquals(listOf("a1", "b1"), selectVariantStopIds(v, "A", "BKK_99"))
    }

    // No headsign match (trip details missing, stop headsign unlike the variant's): the direction
    // through the departure's own platform, not both directions merged.
    private val bothWays = listOf(
        FutarRouteVariant(headsign = "Kelenföld vasútállomás M", stopIds = listOf("north1", "plat_n", "north3"), polyline = FutarPolyline("_p~iF~ps|U_ulLnnqC")),
        FutarRouteVariant(headsign = "Örs vezér tere M+H", stopIds = listOf("south1", "plat_s", "south3"), polyline = FutarPolyline(encoded)),
    )

    @Test fun noHeadsignMatchPrefersTheVariantThroughThePlatform() {
        assertEquals(listOf("south1", "plat_s", "south3"), selectVariantStopIds(bothWays, "Fehér út", servingStopIds = setOf("plat_s")))
        assertEquals(listOf(points), selectVariantPolylines(bothWays, "Fehér út", servingStopIds = setOf("plat_s")))
        assertEquals(listOf(listOf(ll(38.5, -120.2), ll(40.7, -120.95))), selectVariantPolylines(bothWays, null, servingStopIds = setOf("plat_n")))
    }

    @Test fun aHeadsignMatchStillWinsOverThePlatform() =
        assertEquals(listOf("north1", "plat_n", "north3"), selectVariantStopIds(bothWays, "Kelenföld vasútállomás M", servingStopIds = setOf("plat_s")))

    @Test fun aPlatformNoVariantServesFallsBackToAll() =
        assertEquals(listOf("north1", "plat_n", "north3", "south1", "plat_s", "south3"), selectVariantStopIds(bothWays, "Fehér út", servingStopIds = setOf("elsewhere")))

    // ---- selectTripPolyline / selectTripStopIds ----
    private fun trip(entry: FutarTripDetailsEntry) = FutarTripDetailsResponse(data = FutarTripDetailsData(entry = entry))

    @Test fun tripPolylineDecodes() =
        assertEquals(points, selectTripPolyline(trip(FutarTripDetailsEntry(tripId = "trip-1", polyline = FutarPolyline(encoded)))))

    @Test fun tripPolylineEmpty() {
        assertEquals(emptyList(), selectTripPolyline(null))
        assertEquals(emptyList(), selectTripPolyline(trip(FutarTripDetailsEntry(tripId = "trip-1"))))
        assertEquals(emptyList(), selectTripPolyline(trip(FutarTripDetailsEntry(tripId = "trip-1", polyline = FutarPolyline("")))))
    }

    @Test fun tripStopIdsOrderedDeduped() {
        val t = trip(FutarTripDetailsEntry(tripId = "trip-1", stopTimes = listOf("a", "b", "a", "", "c").map { FutarTripStopTime(stopId = it) }))
        assertEquals(listOf("a", "b", "c"), selectTripStopIds(t))
    }

    @Test fun tripStopIdsEmpty() {
        assertEquals(emptyList(), selectTripStopIds(null))
        assertEquals(emptyList(), selectTripStopIds(trip(FutarTripDetailsEntry(tripId = "trip-1"))))
    }

    // ---- resolveStopRefs ----
    private val refs = mapOf(
        "a" to FutarStop(id = "a", name = "Alpha", lat = 47.5, lon = 19.0),
        "b" to FutarStop(id = "b", name = "Zero", lat = 0.0, lon = 0.0),
        "c" to FutarStop(id = "c", name = "Gamma", lat = 47.51, lon = 19.01),
    )

    @Test fun resolveStopRefsOrdered() = assertEquals(
        listOf(ResolvedStopRef("c", "Gamma", 47.51, 19.01), ResolvedStopRef("a", "Alpha", 47.5, 19.0)),
        resolveStopRefs(listOf("c", "missing", "b", "a"), refs),
    )

    @Test fun resolveStopRefsNoRefs() = assertEquals(emptyList(), resolveStopRefs(listOf("a"), null))

    // ---- placeVehiclesOnPolyline ----
    private val north = listOf(ll(47.49, 19.04), ll(47.5, 19.04))

    @Test fun placesAndPointsNorth() {
        val placed = placeVehiclesOnPolyline(listOf(VehiclePlacementInput("v1", 47.495, 19.0405)), north)[0]
        assertEquals("v1", placed.id)
        assertCloseTo(19.04, placed.lon, 4)
        assertCloseTo(0.0, placed.bearing, 0)
    }

    @Test fun pointsEastboundEast() {
        val placed = placeVehiclesOnPolyline(listOf(VehiclePlacementInput("v1", 47.49, 19.041)), listOf(ll(47.49, 19.04), ll(47.49, 19.05)))[0]
        assertCloseTo(90.0, placed.bearing, 0)
    }

    @Test fun keepsRawWithoutPolyline() = assertEquals(
        listOf(PlacedVehicle("v1", 47.49, 19.04, 0.0)),
        placeVehiclesOnPolyline(listOf(VehiclePlacementInput("v1", 47.49, 19.04)), emptyList()),
    )

    @Test fun keepsDirectionAtEnd() {
        val placed = placeVehiclesOnPolyline(listOf(VehiclePlacementInput("v1", 47.5, 19.04)), north)[0]
        assertCloseTo(0.0, placed.bearing, 0)
    }

    @Test fun pointsPastEndAlongLine() {
        val placed = placeVehiclesOnPolyline(listOf(VehiclePlacementInput("v1", 47.51, 19.04)), north)[0]
        assertCloseTo(47.5, placed.lat, 5)
        assertCloseTo(0.0, placed.bearing, 0)
    }

    // ---- bearings ----
    @Test fun cardinalBearings() {
        val o = ll(47.49, 19.04)
        assertCloseTo(0.0, bearingDegrees(o, ll(47.5, 19.04)), 0)
        assertCloseTo(90.0, bearingDegrees(o, ll(47.49, 19.05)), 0)
        assertCloseTo(180.0, bearingDegrees(o, ll(47.48, 19.04)), 0)
        assertCloseTo(270.0, bearingDegrees(o, ll(47.49, 19.03)), 0)
    }

    @Test fun bearingAlongBentPolyline() {
        val line = listOf(ll(47.49, 19.04), ll(47.50, 19.04), ll(47.50, 19.06))
        val d = cumulativeHaversineDistances(line)
        assertCloseTo(0.0, bearingAlongPolyline(line, d, d[1] / 2), 0)
        assertCloseTo(90.0, bearingAlongPolyline(line, d, d[1] + (d[2] - d[1]) / 2), 0)
        assertCloseTo(90.0, bearingAlongPolyline(line, d, d[2]), 0)
        assertEquals(0.0, bearingAlongPolyline(emptyList(), emptyList(), 0.0))
        assertEquals(0.0, bearingAlongPolyline(listOf(ll(47.5, 19.0)), listOf(0.0), 0.0))
    }

    @Test fun angleDifferences() {
        assertEquals(0.0, angleDifference(0.0, 0.0))
        assertEquals(90.0, angleDifference(0.0, 90.0))
        assertEquals(90.0, angleDifference(90.0, 0.0))
        assertEquals(180.0, angleDifference(0.0, 180.0))
        assertEquals(20.0, angleDifference(10.0, 350.0))
        assertEquals(20.0, angleDifference(350.0, 10.0))
        assertEquals(180.0, angleDifference(45.0, 225.0))
    }

    @Test fun snapPrefersMatchingBearing() {
        val line = listOf(ll(47.49, 19.04), ll(47.5, 19.04), ll(47.49, 19.0402))
        val veh = ll(47.495, 19.04005)
        assertCloseTo(19.04, snapToPolyline(veh, line, 0.0)?.point?.lon, 4)
        assertCloseTo(19.0401, snapToPolyline(veh, line, 180.0)?.point?.lon, 4)
    }

    @Test fun telemetryWithin45SnapsToRoad() {
        val placed = placeVehiclesOnPolyline(listOf(VehiclePlacementInput("v1", 47.495, 19.04, 5.0)), north)[0]
        assertCloseTo(0.0, placed.bearing, 0)
    }

    @Test fun oppositeTelemetryWins() {
        val placed = placeVehiclesOnPolyline(listOf(VehiclePlacementInput("v1", 47.495, 19.04, 180.0)), north)[0]
        assertEquals(180.0, placed.bearing)
    }

    // ---- journeyFitKey ----
    @Test fun fitKeyEmpty() {
        assertEquals("", journeyFitKey(emptyList()))
        assertEquals("", journeyFitKey(listOf(emptyList(), listOf(ll(1.0, 1.0)))))
    }

    @Test fun fitKeyCoversEveryPolyline() {
        val one = listOf(listOf(ll(47.5, 19.05), ll(47.51, 19.06), ll(47.52, 19.07)))
        val two = one + listOf(listOf(ll(47.49, 19.05), ll(47.48, 19.04), ll(47.47, 19.03)))
        assertNotEquals(journeyFitKey(one), journeyFitKey(two))
    }

    @Test fun fitKeyChangesWithWalks() {
        val polylines = listOf(listOf(ll(47.5, 19.05), ll(47.51, 19.06)))
        val noWalks = journeyFitKey(polylines)
        val withWalk = journeyFitKey(polylines, listOf(listOf(ll(47.49, 19.04), ll(47.5, 19.05))))
        val moved = journeyFitKey(polylines, listOf(listOf(ll(47.48, 19.03), ll(47.5, 19.05))))
        assertNotEquals(noWalks, withWalk)
        assertNotEquals(withWalk, moved)
    }

    @Test fun fitKeyStable() {
        val a = journeyFitKey(listOf(listOf(ll(47.5, 19.05), ll(47.51, 19.06), ll(47.52, 19.07))), listOf(listOf(ll(47.49, 19.04), ll(47.5, 19.05))))
        val b = journeyFitKey(listOf(listOf(LatLon(47.5, 19.05), LatLon(47.51, 19.06), LatLon(47.52, 19.07))), listOf(listOf(LatLon(47.49, 19.04), LatLon(47.5, 19.05))))
        assertEquals(a, b)
        assertTrue(a.isNotEmpty())
        assertEquals("47.50000,19.05000:47.52000,19.07000:3|w47.49000,19.04000-47.50000,19.05000", a)
    }

    @Test fun toFixedRoundsHalfAwayFromZero() {
        assertEquals("1.2", toFixed(1.2, 1))
        assertEquals("-0.50000", toFixed(-0.5, 5))
        assertEquals("3", toFixed(2.5, 0))
        assertEquals("-3", toFixed(-2.5, 0))
        assertEquals(3.0, roundHalfUp(2.5))
        assertEquals(-2.0, roundHalfUp(-2.5))
    }
}
