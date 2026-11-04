package dev.southstar21.vectra.core.data

import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineCacheTest {
    private val kv = KeyValueStore(FakeDataStore())
    private val cache = OfflineCache(kv, "vectra_test_cache", ListSerializer(Int.serializer()))

    @Test fun aSnapshotRoundTrips() = runTest {
        cache.write("u1", listOf(1, 2))
        assertEquals(listOf(1, 2), cache.read("u1"))
    }

    @Test fun usersAreKeptApart() = runTest {
        cache.write("u1", listOf(1))
        assertNull(cache.read("u2"))
    }

    @Test fun aCorruptSnapshotReadsAsMissing() = runTest {
        kv.set("vectra_test_cache_u1", "{not json")
        assertNull(cache.read("u1"))
    }

    @Test fun clearingRemovesIt() = runTest {
        cache.write("u1", listOf(1))
        cache.clear("u1")
        assertNull(cache.read("u1"))
    }

    @Test fun onlineFetchesAndWritesThrough() = runTest {
        cache.write("u1", listOf(1))
        assertEquals(listOf(2), offlineFirst(true, cache, "u1") { listOf(2) })
        assertEquals(listOf(2), cache.read("u1"))
    }

    @Test fun aFailedFetchFallsBackToTheCache() = runTest {
        cache.write("u1", listOf(1))
        assertEquals(listOf(1), offlineFirst(true, cache, "u1") { error("down") })
    }

    @Test fun aFailedFetchWithNoCacheSurfaces() = runTest {
        assertEquals("down", assertFailsWith<IllegalStateException> { offlineFirst(true, cache, "u1") { error("down") } }.message)
    }

    @Test fun offlineReadsTheCacheWithoutFetching() = runTest {
        cache.write("u1", listOf(1))
        assertEquals(listOf(1), offlineFirst(false, cache, "u1") { error("must not fetch") })
    }

    @Test fun offlineWithNoCacheStillTries() = runTest {
        assertEquals(listOf(9), offlineFirst(false, cache, "u1") { listOf(9) })
    }

    @Test fun cancellationIsNotAFailedFetch() = runTest {
        cache.write("u1", listOf(1))
        assertFailsWith<CancellationException> { offlineFirst(true, cache, "u1") { throw CancellationException("stop") } }
    }

    @Test fun everyCacheBaseIsWipedOnSignOut() = runTest {
        for (base in LargeValueKeyPrefixes) assertTrue(UserScopedKeyPrefixes.any(base::startsWith), "$base survives sign-out")
    }
}
