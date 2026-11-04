package dev.southstar21.vectra.core.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.yield

class RunSuspendCatchingTest {
    @Test fun aValueIsASuccess() = runTest {
        assertEquals(1, runSuspendCatching { 1 }.getOrNull())
    }

    @Test fun aFailureIsCaptured() = runTest {
        assertEquals("boom", runSuspendCatching { error("boom") }.exceptionOrNull()?.message)
    }

    @Test fun theCallersCancellationPropagates() = runTest {
        var ranOn = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            runSuspendCatching { awaitCancellation() }
            ranOn = true
        }
        job.cancelAndJoin()
        assertFalse(ranOn)
        assertTrue(job.isCancelled)
    }

    @Test fun awaitingSomethingCancelledIsAFailureNotOurCancellation() = runTest {
        val other = CompletableDeferred<Int>()
        other.cancel()
        val result = runSuspendCatching { other.await() }
        assertIs<CancellationException>(result.exceptionOrNull())
    }

    @Test fun aTimeoutInsideIsAFailure() = runTest {
        val result = runSuspendCatching { withTimeout(10) { awaitCancellation() } }
        assertTrue(result.isFailure)
    }

    @Test fun aCancelledScopeStopsTheNextStepOfALoop() = runTest {
        val steps = mutableListOf<Int>()
        val job: Job = launch {
            for (i in 1..3) {
                runSuspendCatching { steps += i; if (i == 2) this@launch.cancel(); yield() }
            }
        }
        job.join()
        assertEquals(listOf(1, 2), steps)
    }

    @Test fun usableInsideAsync() = runTest {
        assertEquals(2, async { runSuspendCatching { 2 }.getOrThrow() }.await())
    }
}
