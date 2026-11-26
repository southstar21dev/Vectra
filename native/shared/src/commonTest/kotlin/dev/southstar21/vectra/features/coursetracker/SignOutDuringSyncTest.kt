package dev.southstar21.vectra.features.coursetracker

import dev.southstar21.vectra.core.data.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignOutDuringSyncTest {
    @Test fun aSyncFinishingAfterSignOutLeavesNoCacheForTheOldAccount() = runTest {
        val kv = KeyValueStore(FakeDataStore())
        val cloud = FakeCloud()
        val uid = MutableStateFlow<String?>("user-a")
        val repo = CourseTrackerRepository(cloud, PassThroughCipher, kv, backgroundScope, uid, isOnline = { true })
        runCurrent()
        // The account signs out and its cache is cleared while the sync is reading the cloud.
        cloud.duringFetch = {
            uid.value = null
            kv.set(courseTrackerCacheKey("user-a"), null)
        }
        val events = listOf(event("Algebra", "ALG1", 1, "08:00", "09:30", "2026/27/1", "2026-09-07", "2026-12-07", "lecture", source = "neptun"))
        repo.syncFromScheduleEvents(events)
        runCurrent()
        assertNull(kv.get(courseTrackerCacheKey("user-a")))
    }

    @Test fun aTrackerTheSyncDroppedStaysDroppedWhenItsUploadWasHeldBack() = runTest {
        val kv = KeyValueStore(FakeDataStore())
        val stale = CourseTrackerData(1, mapOf("exam_key" to createDefaultTracker("Business Economics - Vizsga")))
        kv.set(courseTrackerCacheKey("user-a"), serializeTrackerData(stale))
        val cloud = FakeCloud().apply { row = serializeTrackerData(stale); hasRow = true; fetchError = IllegalStateException("offline") }
        val repo = startRepo(kv, cloud)
        // Offline: the sync drops the stale exam tracker, but its upload is held back.
        repo.syncFromScheduleEvents(listOf(event("Algebra", "ALG1", 1, "08:00", "09:30", "2026/27/1", "2026-09-07", "2026-12-07", "lecture", source = "neptun")))
        runCurrent()
        assertTrue("exam_key" !in repo.data.value.trackers)
        // Back online, the refresh reads the cloud copy that still has it: it must not come back.
        cloud.fetchError = null
        repo.refresh()
        runCurrent()
        assertTrue("exam_key" !in repo.data.value.trackers)
    }

    private fun TestScope.startRepo(kv: KeyValueStore, cloud: FakeCloud): CourseTrackerRepository {
        val repo = CourseTrackerRepository(cloud, PassThroughCipher, kv, backgroundScope, MutableStateFlow("user-a"), isOnline = { true })
        runCurrent()
        return repo
    }
}
