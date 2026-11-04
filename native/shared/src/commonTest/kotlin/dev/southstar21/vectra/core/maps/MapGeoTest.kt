package dev.southstar21.vectra.core.maps

import androidx.compose.ui.graphics.Color
import dev.southstar21.vectra.features.transit.assertCloseTo
import dev.southstar21.vectra.features.transit.parseHexColor
import dev.southstar21.vectra.features.transit.withHash
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.southstar21.vectra.core.designsystem.theme.Palette

/** Map GeoJSON builders, map colours and vehicle marker colours. */
class MapGeoTest {
    private fun JsonObject.features() = this["features"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.coords() = this["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
    private fun JsonObject.props() = this["properties"]!!.jsonObject
    private fun JsonArray.pair() = map { it.jsonPrimitive.double }

    @Test fun lineCollectionDropsShortAndEmitsLonLat() {
        val fc = lineFeatureCollection(listOf(listOf(LatLon(47.5, 19.05), LatLon(47.6, 19.1)), listOf(LatLon(1.0, 1.0))))
        assertEquals(1, fc.features().size)
        assertEquals(listOf(listOf(19.05, 47.5), listOf(19.1, 47.6)), fc.features()[0].coords().map { it.jsonArray.pair() })
        assertEquals("FeatureCollection", fc["type"]!!.jsonPrimitive.content)
    }

    @Test fun lineCollectionAttachesPerIndexProperties() {
        val fc = lineFeatureCollection(
            listOf(listOf(LatLon(1.0, 1.0), LatLon(2.0, 2.0)), listOf(LatLon(3.0, 3.0), LatLon(4.0, 4.0))),
        ) { i -> buildJsonObject { put("color", if (i == 0) "#111" else "#222") } }
        assertEquals(listOf("#111", "#222"), fc.features().map { it.props()["color"]!!.jsonPrimitive.content })
    }

    @Test fun stopCollectionMapsKindAndPriority() {
        val fc = stopFeatureCollection(
            listOf(
                MapStop(id = "a", name = "A", lat = 47.5, lon = 19.05, kind = MapStopKind.Selected),
                MapStop(name = "B", lat = 47.5, lon = 19.05),
                MapStop(name = "Zero", lat = 0.0, lon = 0.0),
            ),
        )
        val f = fc.features()
        assertEquals(2, f.size)
        assertEquals("a", f[0].props()["id"]!!.jsonPrimitive.content)
        assertEquals("selected", f[0].props()["kind"]!!.jsonPrimitive.content)
        assertEquals(4, f[0].props()["labelPriority"]!!.jsonPrimitive.int)
        assertEquals("", f[1].props()["id"]!!.jsonPrimitive.content)
        assertEquals("regular", f[1].props()["kind"]!!.jsonPrimitive.content)
        assertEquals(1, f[1].props()["labelPriority"]!!.jsonPrimitive.int)
    }

    @Test fun accuracyCircleNullForInvalid() {
        assertNull(accuracyCircleFeature(LatLon(47.5, 19.05), 0.0))
        assertNull(accuracyCircleFeature(LatLon(0.0, 0.0), 50.0))
    }

    @Test fun accuracyCircleClosedRing() {
        val f = assertNotNull(accuracyCircleFeature(LatLon(47.5, 19.05), 100.0))
        val ring = f.coords()[0].jsonArray
        assertEquals(65, ring.size)
        assertEquals(ring[0].jsonArray.pair(), ring[64].jsonArray.pair())
    }

    @Test fun boundsOfPoints() {
        assertNull(boundsOf(emptyList()))
        assertEquals(Bounds(47.0, 19.0, 48.0, 20.0), boundsOf(listOf(LatLon(47.0, 19.0), LatLon(48.0, 20.0))))
    }

    private val line = listOf(LatLon(47.5, 19.05), LatLon(47.6, 19.1))

    @Test fun samePointsSameReference() = assertTrue(samePoints(line, line))
    @Test fun samePointsEqualContent() = assertTrue(samePoints(line, listOf(LatLon(47.5, 19.05), LatLon(47.6, 19.1))))
    @Test fun samePointsDifferentLength() = assertFalse(samePoints(line, listOf(line[0])))
    @Test fun samePointsDifferentPoint() = assertFalse(samePoints(line, listOf(line[0], LatLon(47.6, 19.11))))

    @Test fun cutLineNullWhenTooShort() {
        assertNull(cutLineFeature(line, cumulativeHaversineDistances(line), -5.0))
    }

    @Test fun cutLineLonLatOrder() {
        val d = cumulativeHaversineDistances(line)
        val f = assertNotNull(cutLineFeature(line, d, d.last() / 2, buildJsonObject { put("color", "#fff") }))
        assertCloseTo(19.05, f.coords()[0].jsonArray.pair()[0], 3)
        assertEquals("#fff", f.props()["color"]!!.jsonPrimitive.content)
    }

    @Test fun labelPriorities() {
        assertEquals(4, labelPriority(MapStopKind.Selected))
        assertEquals(3, labelPriority(MapStopKind.Tracked))
        assertEquals(2, labelPriority(MapStopKind.Terminus))
        assertEquals(1, labelPriority(MapStopKind.Regular))
        assertEquals(0, labelPriority(MapStopKind.Secondary))
        assertEquals(0, labelPriority(MapStopKind.SecondaryTerminus))
        assertEquals(1, labelPriority(null))
    }

    // ---- colours ----
    @Test fun withHashAddsOnlyWhenMissing() {
        assertEquals("#ff0000", withHash("ff0000"))
        assertEquals("#ff0000", withHash("#ff0000"))
    }

    // ---- vehicle marker colours ----
    @Test fun vehicleVariantColors() {
        assertEquals(Palette.Emerald500, VehicleVariant.Primary.color())
        assertEquals(Palette.Violet500, VehicleVariant.Secondary.color())
        assertEquals(Palette.Pink500, VehicleVariant.Approximate.color())
    }

    @Test fun routeHexWithOrWithoutHashParses() {
        assertEquals(parseHexColor("009fe3"), parseHexColor(withHash("009fe3")))
        assertEquals(Color(0xFFF7C600), parseHexColor("#f7c600"))
    }

}
