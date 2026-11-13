package dev.southstar21.vectra.features.transit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineStripModelTest {
    private val now = 1_800_000_000_000L
    private fun arrival(minutes: Int, predicted: Boolean = true, headsign: String = "X") =
        Arrival(arrivalMs = now + minutes * 60_000L, headSign = headsign, isPredicted = predicted, tripId = "t$minutes")

    private fun dir(dest: String, minutes: Int?, dist: Double?, next: Int? = null, predicted: Boolean = true) =
        StripDirection(dest, minutes?.let { arrival(it, predicted, dest) }, next?.let { arrival(it, predicted, dest) }, dist)

    @Test
    fun timeLabels() {
        assertEquals("NOW", stripTimeLabel(now + 10_000, now + 20_000))
        assertEquals("4'", stripTimeLabel(now + 4 * 60_000, now))
        assertEquals("99'", stripTimeLabel(now + 99 * 60_000, now))
        assertTrue(stripTimeLabel(now + 120 * 60_000, now).contains(":"))
    }

    @Test
    fun sidesFollowTheDestinationNameNotTheLead() {
        val a = buildStripModel(listOf(dir("Újbuda", 4, 1.0), dir("Széll Kálmán tér", 1, 2.0)), now, metro = false, frozen = false)
        val b = buildStripModel(listOf(dir("Széll Kálmán tér", 1, 2.0), dir("Újbuda", 4, 1.0)), now, metro = false, frozen = false)
        assertEquals(a, b)
        assertEquals("Széll Kálmán tér", a.rightEnd)
        assertEquals("Újbuda", a.leftEnd)
    }

    @Test
    fun vehiclesSitOnTheirApproachSide() {
        val m = buildStripModel(listOf(dir("A", 1, 1.5), dir("B", 4, 2.0)), now, metro = false, frozen = false)
        val right = m.vehicles.single { it.heading > 0 }
        val left = m.vehicles.single { it.heading < 0 }
        assertEquals(1.5f, right.position)
        assertEquals(5f, left.position)
        assertTrue(right.showMarker && left.showMarker)
    }

    @Test
    fun arrivingVehicleSitsOnYourStop() {
        val v = buildStripModel(listOf(dir("A", 0, 0.05)), now, metro = false, frozen = false).vehicles.single()
        assertEquals(StripSideStops.toFloat(), v.position)
        assertEquals("NOW", v.label)
        assertTrue(v.arriving)
    }

    @Test
    fun farVehicleWaitsAtTheEdgeWithItsStopCount() {
        val m = buildStripModel(listOf(dir("A", 14, 7.4)), now, metro = false, frozen = false)
        val v = m.vehicles.single()
        assertEquals(0f, v.position)
        assertEquals("8 stops", v.sub)
        assertTrue(m.fadeLeft)
    }

    @Test
    fun metroDrawsItsTimetablePosition() {
        val m = buildStripModel(listOf(dir("Újpest", 3, 1.0), dir("Kőbánya", 2, 2.0)), now, metro = true, frozen = false)
        assertTrue(m.vehicles.all { it.showMarker && it.metro })
        // Sides follow the destination name: Kőbánya (2 stops off) on the left, Újpest (1) on the right.
        assertEquals(listOf(1f, 4f), m.vehicles.sortedBy { it.heading }.reversed().map { it.position })
    }

    @Test
    fun metroWithoutAnEstimateShowsTimesAtTheEdges() {
        val m = buildStripModel(listOf(dir("Újpest", 3, null, next = 7), dir("Kőbánya", 2, null, next = 6)), now, metro = true, frozen = false)
        assertTrue(m.vehicles.none { it.showMarker })
        assertEquals("then 7'", m.vehicles.single { it.heading < 0 }.sub)
        assertEquals(listOf(0f, 6f), m.vehicles.sortedBy { it.heading }.reversed().map { it.position })
    }

    @Test
    fun noLivePositionPutsTheTimeAtTheEdge() {
        val v = buildStripModel(listOf(dir("A", 12, null, predicted = false)), now, metro = false, frozen = false).vehicles.single()
        assertFalse(v.showMarker)
        assertEquals(StripTimeKind.Scheduled, v.kind)
        assertEquals(0f, v.position)
    }

    @Test
    fun aDueTripWithoutAPositionSitsAtYourStop() {
        // Arrivals and positions update apart: the due trip's vehicle can briefly be missing.
        val d = dir("A", 0, null, next = 2)
        val due = buildStripModel(listOf(d), now, metro = false, frozen = false).vehicles.single()
        assertEquals(StripSideStops.toFloat(), due.position)
        assertEquals("NOW", due.label)
        assertFalse(due.showMarker)
    }

    @Test
    fun oneVehicleEachWayAtMost() {
        // The one after the next, even when it is on the strip, isn't drawn.
        val m = buildStripModel(listOf(dir("A", 2, 1.0, next = 4), dir("B", 3, 1.5, next = 6)), now, metro = false, frozen = false)
        assertEquals(1, m.vehicles.count { it.heading > 0 })
        assertEquals(1, m.vehicles.count { it.heading < 0 })
    }

    @Test
    fun oneDirectionDimsTheOtherSide() {
        val m = buildStripModel(listOf(dir("A", 7, 2.5)), now, metro = false, frozen = false)
        assertTrue(m.dimRight)
        assertNull(m.leftEnd)
    }

    @Test
    fun aThirdDirectionSharingAnApproachForks() {
        val dirs = listOf(
            dir("Újbuda-központ M", 4, 1.0).copy(approach = "Király utca"),
            dir("Móricz Zsigmond körtér M", 9, 2.5).copy(approach = "Király utca"),
            dir("Széll Kálmán tér M", 1, 1.5).copy(approach = "Jókai tér"),
        )
        val m = buildStripModel(dirs, now, metro = false, frozen = false)
        val fork = m.fork!!
        // The drawn pair comes in from opposite sides; the third splits off its sibling's end.
        assertTrue(setOf(m.leftEnd, m.rightEnd).contains("Széll Kálmán tér M"))
        // One vehicle each way: on the forked side the sooner branch, named after where it goes.
        assertEquals(2, m.vehicles.size)
        assertEquals(shortDestination("Újbuda-központ M"), m.vehicles.single { it.heading == fork.heading }.sub)
        assertTrue(extraDirections(dirs).isEmpty())
        assertEquals("Móricz", shortDestination("Móricz Zsigmond körtér M"))
    }

    @Test
    fun theForkedBranchShowsWhenItIsSooner() {
        val dirs = listOf(
            dir("Újbuda-központ M", 9, 2.5).copy(approach = "Király utca"),
            dir("Móricz Zsigmond körtér M", 4, 1.0).copy(approach = "Király utca"),
            dir("Széll Kálmán tér M", 1, 1.5).copy(approach = "Jókai tér"),
        )
        val m = buildStripModel(dirs, now, metro = false, frozen = false)
        val side = m.vehicles.filter { it.heading == m.fork!!.heading }
        assertEquals(1, side.size)
        assertEquals("Móricz", side.single().sub)
    }

    @Test
    fun aThirdDirectionIsAnExtraNotDrawn() {
        val dirs = listOf(dir("Újbuda", 4, 1.0), dir("Móricz", 9, 2.0), dir("Széll Kálmán tér", 1, 1.5))
        val m = buildStripModel(dirs, now, metro = false, frozen = false)
        val extra = extraDirections(dirs)
        assertEquals(1, extra.size)
        assertTrue(extra.single().destination !in listOfNotNull(m.leftEnd, m.rightEnd))
    }

    @Test
    fun atAFirstStopTheVehicleWaitsAtYourStop() {
        val d = dir("Jászai Mari tér", 6, 1.0).copy(origin = true)
        val m = buildStripModel(listOf(d), now, metro = false, frozen = false)
        val v = m.vehicles.single()
        assertEquals(StripSideStops.toFloat(), v.position)
        assertEquals("6'", v.label)
        assertEquals("waiting", v.sub)
        // Nothing comes in from before a first stop; the way out stays coloured.
        assertTrue(m.dimLeft)
        assertFalse(m.dimRight)
    }

    @Test
    fun atALastStopVehiclesOnlyArrive() {
        val m = buildStripModel(listOf(dir("Deák Ferenc tér", 5, 1.5).copy(terminus = true)), now, metro = false, frozen = false)
        assertTrue(m.terminus)
        assertTrue(m.dimRight)
        assertEquals(1.5f, m.vehicles.single().position)
    }

    @Test
    fun aVehicleAtYourStopBeforeItsTimeKeepsTheTime() {
        val v = buildStripModel(listOf(dir("A", 1, 0.05)), now, metro = false, frozen = false).vehicles.single()
        assertEquals(StripSideStops.toFloat(), v.position)
        assertEquals("1'", v.label)
    }

    @Test
    fun vehicleNouns() {
        assertEquals("trams", vehicleNoun("TRAM"))
        assertEquals("trains", vehicleNoun("SUBWAY"))
        assertNull(vehicleNoun("UNKNOWN"))
    }

    @Test
    fun frozenDataNeverReadsAsLive() {
        val v = buildStripModel(listOf(dir("A", 3, 1.0)), now, metro = false, frozen = true).vehicles.single()
        assertTrue(v.faded)
        assertEquals(StripTimeKind.Scheduled, v.kind)
    }
}
