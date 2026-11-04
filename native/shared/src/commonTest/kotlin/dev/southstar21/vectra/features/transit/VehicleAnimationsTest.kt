package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.VehicleVariant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VehicleAnimationsTest {
    // A straight west→east line (~760 m) through Budapest.
    private val line = listOf(LatLon(47.5, 19.04), LatLon(47.5, 19.05))

    private fun input(lon: Double, id: String = "v1", variant: VehicleVariant? = null) =
        AnimatedVehicleInput(id, line, LatLon(47.5, lon), variant)

    @Test
    fun firstSightingSnapsOntoTheLine() {
        val a = VehicleAnimator()
        a.sync(listOf(input(19.045)))
        assertFalse(a.step(), "a new vehicle does not animate")
        val f = a.frames().single()
        assertTrue(abs(f.position.lon - 19.045) < 1e-6)
        assertEquals(VehicleVariant.Primary, f.variant)
        // Heading east along the line.
        assertTrue(abs(f.bearing - 90.0) <= 1.0, "bearing ${f.bearing}")
    }

    @Test
    fun aMoveGlidesAndLandsOnTheTarget() {
        val a = VehicleAnimator()
        a.sync(listOf(input(19.041)))
        a.sync(listOf(input(19.048)))
        assertTrue(a.step(a.now()), "a move of hundreds of metres animates")
        // The finishing frame still reports activity; the next one is idle.
        a.step(a.now() + 60_000)
        assertFalse(a.step(a.now() + 60_000), "the glide ends within the 10s cap")
        assertTrue(abs(a.frames().single().position.lon - 19.048) < 1e-6)
    }

    @Test
    fun goneVehiclesAreDroppedAndVariantChangesRebuild() {
        val a = VehicleAnimator()
        a.sync(listOf(input(19.041, "a"), input(19.042, "b")))
        a.sync(listOf(input(19.046, "b", VehicleVariant.Secondary)))
        val f = a.frames().single()
        assertEquals("b", f.id)
        assertEquals(VehicleVariant.Secondary, f.variant)
        // Rebuilt, so it snaps instead of gliding from its old spot.
        assertTrue(abs(f.position.lon - 19.046) < 1e-6)
    }

    @Test
    fun invalidInputsAreSkipped() {
        val a = VehicleAnimator()
        a.sync(listOf(AnimatedVehicleInput("x", line, LatLon(0.0, 0.0)), AnimatedVehicleInput("y", listOf(line[0]), LatLon(47.5, 19.04))))
        assertTrue(a.frames().isEmpty())
    }
}
