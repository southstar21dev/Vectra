package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.platform.GeoFix
import dev.southstar21.vectra.core.platform.GeoPermission
import dev.southstar21.vectra.core.platform.LocationProvider
import dev.southstar21.vectra.core.time.nowMs
import dev.southstar21.vectra.features.coursetracker.FakeDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals

/** The watcher keeps its state on one thread (audit 03 #14). */
class DeviceLocationWatcherTest {
    private class RecordingProvider : LocationProvider {
        val ranOn = mutableListOf<Pair<String, ContinuationInterceptor?>>()
        var watching = 0

        override fun permission() = GeoPermission.Granted

        override suspend fun current(): GeoFix {
            ranOn += "current" to currentCoroutineContext()[ContinuationInterceptor]
            return GeoFix(47.5, 19.05, 10.0, timeMs = nowMs())
        }

        override fun watch(): Flow<GeoFix> = flow {
            ranOn += "watch" to currentCoroutineContext()[ContinuationInterceptor]
            watching += 1
            try {
                awaitCancellation()
            } finally {
                watching -= 1
            }
        }
    }

    @Test fun theWatchAndTheOneShotFixRunOnTheConfinedDispatcher() = runTest {
        val appScope = StandardTestDispatcher(testScheduler, name = "app scope")
        val confined = StandardTestDispatcher(testScheduler, name = "confined")
        val provider = RecordingProvider()
        val watcher = DeviceLocation(
            provider,
            KeyValueStore(FakeDataStore()),
            CoroutineScope(backgroundScope.coroutineContext + appScope),
            confined = confined,
        )

        val release = watcher.acquire()
        runCurrent()
        assertEquals(listOf<Pair<String, ContinuationInterceptor?>>("current" to confined, "watch" to confined), provider.ranOn)
        assertEquals(WatchLocationStatus.Tracking, watcher.state.value.status)
        assertEquals(1, provider.watching)

        release()
        runCurrent()
        assertEquals(0, provider.watching)
    }
}
