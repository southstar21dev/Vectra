package dev.southstar21.vectra.features.transit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackerMonitorTest {
    private val now = 1_791_885_600_000L // Date.UTC(2026, 9, 13, 10, 0, 0) — 12:00 in Budapest (CEST)

    private fun arrival(headSign: String, minutes: Int) =
        Arrival(arrivalMs = now + minutes * 60_000L, headSign = headSign, isPredicted = true, tripId = "$headSign-$minutes")

    @Test fun stopsAreRowsDirectionsAreColumns() {
        val grid = buildMonitorGrid(
            listOf(
                MonitorStopInput("oktogon", null, listOf(arrival("Széll Kálmán tér", 2), arrival("Újbuda-központ", 11), arrival("Széll Kálmán tér", 7))),
                MonitorStopInput("blaha", null, listOf(arrival("Újbuda-központ", 8), arrival("Széll Kálmán tér", 5))),
            ),
        )
        assertEquals(listOf("Széll Kálmán tér", "Újbuda-központ"), grid.columns)
        assertEquals(listOf("oktogon", "blaha"), grid.rows.map { it.stopId })
        assertEquals(listOf("Széll Kálmán tér-2", "Újbuda-központ-11"), grid.rows[0].cells.map { it?.tripId })
        assertEquals(listOf("Széll Kálmán tér-5", "Újbuda-központ-8"), grid.rows[1].cells.map { it?.tripId })
        assertEquals(0, grid.extraColumns)
    }

    @Test fun tracksDirectionFirstAndLeavesGaps() {
        val grid = buildMonitorGrid(
            listOf(
                MonitorStopInput("a", "Újbuda-központ", listOf(arrival("Széll Kálmán tér", 1), arrival("Újbuda-központ", 4))),
                MonitorStopInput("b", null, listOf(arrival("Újbuda-központ", 3))),
            ),
        )
        assertEquals(listOf("Újbuda-központ", "Széll Kálmán tér"), grid.columns)
        assertNull(grid.rows[1].cells[1])
    }

    @Test fun countsExtraColumns() {
        val grid = buildMonitorGrid(listOf(MonitorStopInput("astoria", null, listOf("A", "B", "C", "D").mapIndexed { i, h -> arrival(h, i + 1) })))
        assertEquals(MonitorMaxColumns, grid.columns.size)
        assertEquals(1, grid.extraColumns)
    }

    @Test fun formatsMonitorTime() {
        assertEquals("NOW", formatMonitorTime(now + 10_000, now + 30_000))
        assertEquals("7'", formatMonitorTime(now + 7 * 60_000, now))
        assertEquals("59'", formatMonitorTime(now + 59 * 60_000, now))
        assertEquals("13:14", formatMonitorTime(now + 74 * 60_000, now))
    }
}
