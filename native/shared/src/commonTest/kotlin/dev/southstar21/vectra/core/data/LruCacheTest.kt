package dev.southstar21.vectra.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LruCacheTest {
    @Test fun evictsTheOldestBeyondCapacity() {
        val lru = LruCache<String, Int>(2)
        lru["a"] = 1
        lru["b"] = 2
        lru["c"] = 3
        assertNull(lru["a"])
        assertEquals(2, lru["b"])
        assertEquals(3, lru["c"])
    }

    @Test fun readingKeepsAnEntryAlive() {
        val lru = LruCache<String, Int>(2)
        lru["a"] = 1
        lru["b"] = 2
        assertEquals(1, lru["a"])
        lru["c"] = 3
        assertEquals(1, lru["a"])
        assertNull(lru["b"])
    }

    @Test fun rewritingRefreshesWithoutGrowing() {
        val lru = LruCache<String, Int>(2)
        lru["a"] = 1
        lru["b"] = 2
        lru["a"] = 9
        lru["c"] = 3
        assertEquals(2, lru.size)
        assertEquals(9, lru["a"])
        assertNull(lru["b"])
    }

    @Test fun clearEmpties() {
        val lru = LruCache<String, Int>(2)
        lru["a"] = 1
        lru.clear()
        assertNull(lru["a"])
        assertEquals(0, lru.size)
    }
}
