package dev.southstar21.vectra.core.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RetryingTest {
    @Test fun theFirstSuccessIsReturnedWithoutWaiting() = runTest {
        val value = retrying(BackoffPolicy.exponential(3, 1000)) { 7 }
        assertEquals(7, value)
        assertEquals(0, currentTime)
    }

    @Test fun exponentialWaitsDoubleBetweenTries() = runTest {
        var tries = 0
        val value = retrying(BackoffPolicy.exponential(4, 1000)) { attempt ->
            tries++
            if (attempt < 3) error("no") else "ok"
        }
        assertEquals("ok", value)
        assertEquals(4, tries)
        assertEquals(1000L + 2000 + 4000, currentTime)
    }

    @Test fun linearWaitsGrowByTheStep() = runTest {
        assertFailsWith<IllegalStateException> {
            retrying(BackoffPolicy.linear(3, 1500)) { error("down") }
        }
        assertEquals(1500L + 3000, currentTime)
    }

    @Test fun theLastFailureIsThrownAfterTheLastTry() = runTest {
        var tries = 0
        val e = assertFailsWith<IllegalStateException> {
            retrying(BackoffPolicy.exponential(3, 10)) { error("try ${++tries}") }
        }
        assertEquals("try 3", e.message)
    }

    @Test fun aFailureThatCannotBeCuredIsNotRetried() = runTest {
        var tries = 0
        assertFailsWith<IllegalArgumentException> {
            retrying(BackoffPolicy.exponential(5, 10), retryIf = { it !is IllegalArgumentException }) {
                tries++
                throw IllegalArgumentException("a 4xx")
            }
        }
        assertEquals(1, tries)
        assertEquals(0, currentTime)
    }

    @Test fun aTimeoutIsAFailureToRetryNotACancellation() = runTest {
        var tries = 0
        val value = retrying(BackoffPolicy.linear(3, 100, attemptTimeoutMs = 500)) {
            if (++tries < 3) awaitCancellation() else "third time"
        }
        assertEquals("third time", value)
        assertEquals(500L + 100 + 500 + 200, currentTime)
    }

    @Test fun aTimeoutOnEveryTryEndsAsTheLastFailure() = runTest {
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            retrying(BackoffPolicy.linear(2, 100, attemptTimeoutMs = 50)) { awaitCancellation() }
        }
    }

    @Test fun theCallersOwnCancellationStopsTheRetries() = runTest {
        var tries = 0
        val started = CompletableDeferred<Unit>()
        val job = launch {
            retrying(BackoffPolicy.exponential(5, 1000)) {
                tries++
                started.complete(Unit)
                error("always")
            }
        }
        started.await()
        job.cancel()
        job.join()
        assertEquals(1, tries)
    }

    @Test fun aCancelledSharedDeferredIsRetriedLikeAnyFailure() = runTest {
        val shared = async { delay(10_000) }
        shared.cancel()
        var tries = 0
        val value = retrying(BackoffPolicy.exponential(2, 10)) {
            if (++tries == 1) shared.await() else "second"
        }
        assertEquals("second", value)
    }

    @Test fun rethrowIfOwnCancellationOnlyRethrowsForAnActiveCancelledCaller() = runTest {
        // Not cancelled: a stray CancellationException (a cancelled Deferred) is just a failure.
        rethrowIfOwnCancellation(kotlinx.coroutines.CancellationException("someone else"))
        val job = launch {
            try {
                awaitCancellation()
            } catch (e: kotlinx.coroutines.CancellationException) {
                assertFailsWith<kotlinx.coroutines.CancellationException> { rethrowIfOwnCancellation(e) }
                assertTrue(!currentCoroutineContext()[kotlinx.coroutines.Job]!!.isActive)
                throw e
            }
        }
        job.cancel()
        job.join()
    }
}
