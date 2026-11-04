package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.cumulativeHaversineDistances
import kotlin.test.Test
import kotlin.test.assertEquals

class VehicleAnimMathTest {
    @Test fun snapsWithoutPrevious() =
        assertEquals(VehicleTransition(120.0, 120.0, 1000.0, 0.0, true), transitionFor(null, 120.0, 1000.0))

    @Test fun snapsUnderOneMeter() {
        val t = transitionFor(100.0, 100.4, 2000.0)
        assertEquals(100.4, t.fromDist)
        assertEquals(100.4, t.toDist)
        assertEquals(true, t.done)
        assertEquals(0.0, t.durationMs)
    }

    @Test fun animatesAndClamps() {
        assertEquals(VehicleAnimMinMs, transitionFor(0.0, 5.0, 0.0).durationMs)
        assertEquals(VehicleAnimMaxMs, transitionFor(0.0, 200_000.0, 0.0).durationMs)
        assertEquals(800.0, transitionFor(0.0, 8000.0, 0.0).durationMs)
    }

    @Test fun easesSymmetrically() {
        assertEquals(0.0, easeInOutQuad(0.0))
        assertEquals(0.5, easeInOutQuad(0.5))
        assertEquals(1.0, easeInOutQuad(1.0))
        assertCloseTo(0.125, easeInOutQuad(0.25), 6)
    }

    private val lineNorth = listOf(LatLon(47.49, 19.04), LatLon(47.50, 19.04))
    private val distsNorth = cumulativeHaversineDistances(lineNorth)

    @Test fun polylineBearingWithoutTelemetry() = assertCloseTo(0.0, resolveBearing(lineNorth, distsNorth, distsNorth[1] / 2), 0)
    @Test fun snapsToPolylineWhenClose() = assertCloseTo(0.0, resolveBearing(lineNorth, distsNorth, distsNorth[1] / 2, 8.0), 0)
    @Test fun keepsTelemetryWhenOpposite() = assertEquals(180.0, resolveBearing(lineNorth, distsNorth, distsNorth[1] / 2, 180.0))

    @Test fun telemetryOnShortPolyline() {
        assertEquals(120.0, resolveBearing(emptyList(), emptyList(), 0.0, 120.0))
        assertEquals(90.0, resolveBearing(listOf(LatLon(47.49, 19.04)), listOf(0.0), 0.0, 90.0))
    }
}
