package dev.southstar21.vectra.core.data

import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BlobStoreTest {
    private val dir = Path(SystemTemporaryDirectory, "vectra-blob-test-${Random.nextLong().toULong()}")
    private val blobs = BlobStore(dir)

    @AfterTest fun cleanUp() {
        runCatching {
            SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
            SystemFileSystem.delete(dir, mustExist = false)
        }
    }

    private fun onDisk(test: suspend () -> Unit) = runTest { withContext(Dispatchers.Default) { test() } }

    @Test fun roundTripsAndReplaces() = onDisk {
        assertNull(blobs.get("a"))
        blobs.set("a", "one")
        blobs.set("a", "two")
        assertEquals("two", blobs.get("a"))
        assertEquals(listOf("a"), SystemFileSystem.list(dir).map { it.name })
    }

    @Test fun flushesTheDataBeforeTheRenameAndTheDirectoryAfter() = onDisk {
        blobs.set("q", "old")
        val steps = mutableListOf<String>()
        val durable = BlobStore(
            dir,
            flushFile = { temp ->
                // The whole new value is in the temp file while the target still holds the old one.
                val written = SystemFileSystem.source(temp).buffered().use { it.readString() }
                steps += "file:$written, target:${SystemFileSystem.source(Path(dir, "q")).buffered().use { it.readString() }}"
            },
            flushDirectory = { steps += "dir:${SystemFileSystem.source(Path(dir, "q")).buffered().use { it.readString() }}" },
        )
        durable.set("q", "new")
        assertEquals(listOf("file:new, target:old", "dir:new"), steps)
    }

    @Test fun aFailedFlushKeepsTheOldValueAndReportsIt() = onDisk {
        blobs.set("q", "old")
        val failing = BlobStore(dir, flushFile = { throw IOException("disk full") })
        assertFailsWith<IOException> { failing.set("q", "new") }
        assertEquals("old", blobs.get("q"))
    }

    @Test fun nullRemoves() = onDisk {
        blobs.set("a", "one")
        blobs.set("a", null)
        assertNull(blobs.get("a"))
    }

    @Test fun keepsUnicodeAndLargeValues() = onDisk {
        val value = "árvíztűrő tükörfúrógép ".repeat(50_000)
        blobs.set("big", value)
        assertEquals(value, blobs.get("big"))
    }

    @Test fun oddKeysStayInsideTheDirectory() = onDisk {
        blobs.set("../up/and:out é", "v")
        assertEquals("v", blobs.get("../up/and:out é"))
        assertEquals(1, SystemFileSystem.list(dir).size)
    }

    @Test fun removesByPrefix() = onDisk {
        blobs.set("cache_u1", "1")
        blobs.set("cache_u2", "2")
        blobs.set("other", "3")
        blobs.removeByPrefix("cache", "missing")
        assertNull(blobs.get("cache_u1"))
        assertNull(blobs.get("cache_u2"))
        assertEquals("3", blobs.get("other"))
    }

    @Test fun removeByPrefixBeforeAnyWrite() = onDisk {
        blobs.removeByPrefix("cache")
    }

    @Test fun keyValueStoreSendsOnlyLargeKeysToFiles() = onDisk {
        val kv = KeyValueStore(FakeDataStore(), blobs)
        kv.set("vectra_schedule_cache_events_u1", "large")
        kv.set("vectra.appearance", "small")
        assertEquals("large", kv.get("vectra_schedule_cache_events_u1"))
        assertEquals("small", kv.get("vectra.appearance"))
        assertEquals("large", blobs.get("vectra_schedule_cache_events_u1"))
        assertNull(blobs.get("vectra.appearance"))
    }

    @Test fun signOutWipeReachesTheFiles() = onDisk {
        val kv = KeyValueStore(FakeDataStore(), blobs)
        kv.set("vectra_trip_plans_cache_u1", "plans")
        kv.set("vectra.deviceLocation", "here")
        kv.removeByPrefix(*UserScopedKeyPrefixes)
        assertNull(kv.get("vectra_trip_plans_cache_u1"))
        assertNull(kv.get("vectra.deviceLocation"))
    }

    @Test fun movesOldValuesOutOfDataStoreOnce() = onDisk {
        val store = FakeDataStore()
        val before = KeyValueStore(store)
        before.set("vectra_schedule_items_u1", "queue")
        before.set("vectra.appearance", "small")

        val kv = KeyValueStore(store, blobs)
        kv.moveLargeValuesToBlobs()
        assertEquals("queue", kv.get("vectra_schedule_items_u1"))
        assertNull(before.get("vectra_schedule_items_u1"))
        assertEquals("small", kv.get("vectra.appearance"))

        // A later run must not resurrect or overwrite anything.
        kv.set("vectra_schedule_items_u1", "newer")
        before.set("vectra_schedule_items_u1", "stale")
        kv.moveLargeValuesToBlobs()
        assertEquals("newer", kv.get("vectra_schedule_items_u1"))
    }

    @Test fun anInterruptedMoveKeepsTheNewerFile() = onDisk {
        val store = FakeDataStore()
        KeyValueStore(store).set("vectra_schedule_items_u1", "old")
        blobs.set("vectra_schedule_items_u1", "newer")
        KeyValueStore(store, blobs).moveLargeValuesToBlobs()
        assertEquals("newer", blobs.get("vectra_schedule_items_u1"))
    }
}
