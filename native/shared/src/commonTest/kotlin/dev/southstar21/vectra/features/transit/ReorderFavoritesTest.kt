package dev.southstar21.vectra.features.transit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ReorderFavoritesTest {
    private fun t(id: String, fav: Boolean) = TransitTracker(
        id = id, userId = "u1", stopId = "s1", stopName = "Stop 1", routeId = "r1", routeShortName = id,
        routeType = "3", dedupeKey = id, createdAt = "2026-01-01T00:00:00Z", isFavorite = fav, sortOrder = 0,
    )

    @Test fun movesDown() = assertEquals(listOf("B", "A", "C"), reorderAllSlots(listOf(t("A", false), t("B", false), t("C", false)), 0, 1).map { it.id })
    @Test fun movesUp() = assertEquals(listOf("A", "C", "B"), reorderAllSlots(listOf(t("A", false), t("B", false), t("C", false)), 2, -1).map { it.id })

    @Test fun outOfBoundsSameInstance() {
        val items = listOf(t("A", false), t("B", false))
        assertSame(items, reorderAllSlots(items, 0, -1))
        assertSame(items, reorderAllSlots(items, 1, 1))
    }

    @Test fun swapsFavoritesKeepingOthers() {
        val items = listOf(t("NF1", false), t("F1", true), t("NF2", false), t("F2", true), t("NF3", false))
        assertEquals(listOf("NF1", "F2", "NF2", "F1", "NF3"), reorderFavoriteSlots(items, 0, 1).map { it.id })
    }

    @Test fun swapsMultipleFavorites() {
        val items = listOf(t("F1", true), t("NF1", false), t("F2", true), t("NF2", false), t("F3", true))
        assertEquals(listOf("F1", "NF1", "F3", "NF2", "F2"), reorderFavoriteSlots(items, 2, -1).map { it.id })
    }

    @Test fun allFavorites() =
        assertEquals(listOf("F2", "F1", "F3"), reorderFavoriteSlots(listOf(t("F1", true), t("F2", true), t("F3", true)), 1, -1).map { it.id })

    @Test fun favoriteOutOfBounds() {
        val items = listOf(t("F1", true), t("F2", true))
        assertSame(items, reorderFavoriteSlots(items, 0, -1))
        assertSame(items, reorderFavoriteSlots(items, 1, 1))
    }

    @Test fun extractsNumbers() {
        assertEquals(1L, extractRouteNumber("1").num)
        assertEquals(1L, extractRouteNumber("M1").num)
        assertEquals(2L, extractRouteNumber("M2").num)
        assertEquals(4L, extractRouteNumber("M4").num)
        assertEquals(5L, extractRouteNumber("H5").num)
        assertEquals(28L, extractRouteNumber("28 / 28A").num)
        assertEquals(37L, extractRouteNumber("37 / 37A").num)
        assertEquals(988L, extractRouteNumber("988").num)
    }

    @Test fun sortsByNumber() {
        val sorted = sortTrackersByNumber(listOf("M2", "H5", "M4", "M1", "37 / 37A", "28 / 28A", "988").map { t(it, false) })
        assertEquals(listOf("M1", "M2", "M4", "H5", "28 / 28A", "37 / 37A", "988"), sorted.map { it.routeShortName })
    }

    @Test fun appliesFavoriteOrder() {
        val a = t("A", true); val b = t("B", false); val c = t("C", true); val d = t("D", true)
        assertEquals(listOf("D", "B", "A", "C"), applyFavoriteOrder(listOf(a, b, c, d), listOf(d, a, c)).map { it.id })
    }
}
