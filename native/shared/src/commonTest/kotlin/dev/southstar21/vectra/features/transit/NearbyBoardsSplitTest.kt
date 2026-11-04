package dev.southstar21.vectra.features.transit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class NearbyBoardsSplitTest {
    private fun board(id: String, vararg arrivals: Long) = NearbyDeparture(
        routeId = id, shortName = id, routeType = "BUS", color = "000000", textColor = "FFFFFF",
        arrivals = arrivals.map { Arrival(arrivalMs = it, headSign = "x", isPredicted = false, tripId = "t$it") },
    )

    @Test fun dropsWhatLeftMoreThanAMinuteAgo() {
        val out = dropDepartedBoards(listOf(board("a", 100_000, 200_000)), now = 170_000)
        assertEquals(listOf(200_000L), out.single().arrivals.map { it.arrivalMs })
    }

    @Test fun aBoardWithNothingLeftDisappears() {
        assertEquals(emptyList(), dropDepartedBoards(listOf(board("a", 100_000)), now = 200_000))
    }

    @Test fun oneMinuteBackStillCounts() {
        assertEquals(1, dropDepartedBoards(listOf(board("a", 100_000)), now = 160_000).size)
    }

    @Test fun nothingToDropKeepsTheSameList() {
        val boards = listOf(board("a", 300_000))
        assertSame(boards, dropDepartedBoards(boards, now = 200_000))
    }
}
