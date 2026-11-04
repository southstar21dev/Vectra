package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PinnedRowsTest {
    private fun tracker(id: String, vararg stops: String) = TransitTracker(
        id = id,
        routeId = "R$id",
        stopId = stops.first(),
        stopName = stops.first(),
        stops = stops.map { TrackerStop(it, it, null) },
    )

    @Test
    fun primaryIsTheStopNearestYou() {
        val coords = mapOf(
            "a" to CatalogStop("a", "A", 47.50, 19.00),
            "b" to CatalogStop("b", "B", 47.60, 19.10),
        )
        val t = tracker("1", "a", "b")
        val rows = buildPinnedRows(listOf(t), listOf(getTrackerStops(t)), emptyMap(), coords, LatLon(47.601, 19.101))
        assertEquals(1, rows[0].primary)
        assertEquals(LatLon(47.60, 19.10), rows[0].primaryAt)
        assertNull(rows[0].soonestMs)
    }

    @Test
    fun withoutAPositionTheFirstStopLeads() {
        val coords = mapOf("a" to CatalogStop("a", "A", 47.5, 19.0))
        val t = tracker("1", "a")
        val rows = buildPinnedRows(listOf(t), listOf(getTrackerStops(t)), emptyMap(), coords, null)
        assertEquals(0, rows[0].primary)
    }

    @Test
    fun rowsWithoutDeparturesKeepTheirOrderAtTheEnd() {
        val ts = listOf(tracker("1", "a"), tracker("2", "b"), tracker("3", "c"))
        val rows = buildPinnedRows(ts, ts.map(::getTrackerStops), emptyMap(), emptyMap(), null)
        assertEquals(listOf("1", "2", "3"), rows.map { it.tracker.id })
    }
}
