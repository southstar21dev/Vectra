package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.features.transit.data.Arrival
import dev.southstar21.vectra.features.transit.strip.divertedDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DivertedTest {
    private fun arrival(at: Long, headsign: String) = Arrival(arrivalMs = at, headSign = headsign, isPredicted = true, tripId = "t$at")

    @Test
    fun laterShortTurnIsNotADiversion() {
        val arrivals = listOf(arrival(1_000, "Bosnyák tér"), arrival(3_000_000, "Hungária körút"))
        assertNull(divertedDestination("Bosnyák tér", arrivals))
    }

    @Test
    fun nextDepartureHeadingElsewhereIsADiversion() {
        val arrivals = listOf(arrival(3_000_000, "Bosnyák tér"), arrival(1_000, "Hungária körút"))
        assertEquals("Hungária körút", divertedDestination("Bosnyák tér", arrivals))
    }

    @Test
    fun noSavedDirectionOrNoArrivalsMeansNoNote() {
        assertNull(divertedDestination(null, listOf(arrival(1, "X"))))
        assertNull(divertedDestination("Bosnyák tér", emptyList()))
    }
}
