package dev.southstar21.vectra.features.transit

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The catalog load keeps trying after its quick retries (audit 03 #4). */
class CatalogRetryTest {
    private val online = MutableStateFlow(true)
    private var attempts = 0
    private var gaveUp = 0

    /** Fails the first [failures] attempts, then answers "catalog". */
    private fun TestScope.start(failures: Int) = async {
        loadCatalogUntilLoaded(online, {
            attempts++
            if (attempts <= failures) error("offline") else "catalog"
        }) { gaveUp++ }
    }

    @Test fun itKeepsTryingAfterTheSecondFailure() = runTest {
        val load = start(failures = 5)
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(1_001)
        assertEquals(2, attempts)
        // The screens stop spinning after the second failure, once.
        assertEquals(1, gaveUp)
        advanceTimeBy(2_000 + 4_000 + 8_000 + 16_000 + 1)
        assertTrue(load.isCompleted)
        assertEquals("catalog", load.await())
        assertEquals(6, attempts)
        assertEquals(1, gaveUp)
    }

    @Test fun offlineItWaitsForTheReconnect() = runTest {
        online.value = false
        val load = start(failures = 2)
        advanceTimeBy(10 * 60_000)
        assertEquals(1, attempts)
        assertFalse(load.isCompleted)
        online.value = true
        runCurrent()
        assertEquals(2, attempts)
        online.value = false
        advanceTimeBy(10 * 60_000)
        assertEquals(2, attempts)
        online.value = true
        runCurrent()
        assertEquals("catalog", load.await())
    }

    @Test fun aReconnectEndsTheWaitEarly() = runTest {
        val load = start(failures = 8)
        // 1 + 2 + 4 + 8 + 16 + 32 + 60 s: eight attempts, the next a minute away.
        advanceTimeBy(123_001)
        assertEquals(8, attempts)
        online.value = false
        runCurrent()
        online.value = true
        runCurrent()
        assertEquals(9, attempts)
        assertEquals("catalog", load.await())
    }

    @Test fun theWaitStopsGrowingAtAMinute() = runTest {
        val load = start(failures = 20)
        advanceTimeBy(123_001)
        assertEquals(8, attempts)
        advanceTimeBy(CatalogRetryMaxMs)
        assertEquals(9, attempts)
        advanceTimeBy(CatalogRetryMaxMs)
        assertEquals(10, attempts)
        load.cancel()
    }
}
