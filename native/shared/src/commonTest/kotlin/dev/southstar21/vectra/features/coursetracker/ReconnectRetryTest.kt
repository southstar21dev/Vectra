package dev.southstar21.vectra.features.coursetracker

import dev.southstar21.vectra.core.data.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconnectRetryTest {
    @Test fun anUploadThatFailedOfflineIsRetriedWhenConnectivityReturns() = runTest {
        val cloud = FakeCloud()
        val online = MutableStateFlow(true)
        val repo = CourseTrackerRepository(
            cloud, PassThroughCipher, KeyValueStore(FakeDataStore()), backgroundScope,
            MutableStateFlow("user-r"), isOnline = { online.value }, onlineChanges = online,
        )
        runCurrent()
        val identity = CourseIdentity("ABC1", "Algebra", "2026/27/1")
        cloud.fetchError = IllegalStateException("no signal")
        online.value = false
        repo.setAttendanceStatus(identity, "2026-11-02", AttendanceStatus.Present, startTime = "10:00")
        advanceTimeBy(TrackerUploadDebounceMs + 100)
        runCurrent()
        assertEquals(0, cloud.uploads.size)

        cloud.fetchError = null
        online.value = true
        runCurrent()
        assertTrue(cloud.uploads.isNotEmpty())
    }
}
