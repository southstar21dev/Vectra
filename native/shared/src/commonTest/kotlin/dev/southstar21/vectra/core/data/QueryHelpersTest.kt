package dev.southstar21.vectra.core.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QueryHelpersTest {
    // ---- StaleGate -------------------------------------------------------------

    @Test fun neverLandedAndInvalidatedDataIsStale() {
        val gate = StaleGate(1_000) { 500 }
        assertTrue(gate.isStale(0))
        assertTrue(gate.isStale(400, invalidated = true))
    }

    @Test fun dataIsStaleExactlyAtTheStaleTime() {
        var now = 10_000L
        val gate = StaleGate(1_000) { now }
        assertFalse(gate.isStale(9_500))
        now = 10_499
        assertFalse(gate.isStale(9_500))
        now = 10_500
        assertTrue(gate.isStale(9_500))
    }

    @Test fun aZeroStaleTimeIsAlwaysStale() = assertTrue(StaleGate(0) { 5 }.isStale(5))

    // ---- QueryState --------------------------------------------------------------

    @Test fun aFirstRequestIsLoadingAndALaterOneOnlyFetching() {
        val first = QueryState<Int>().fetching()
        assertTrue(first.isLoading)
        assertTrue(first.isFetching)
        val later = succeededState(1, 5).fetching()
        assertFalse(later.isLoading)
        assertTrue(later.isFetching)
        assertEquals(1, later.data)
    }

    @Test fun successReplacesEverythingIncludingTheError() {
        val failed = QueryState<Int>().fetching().failed(IllegalStateException("x"))
        val ok = succeededState(7, 9)
        assertTrue(failed.isError)
        assertFalse(ok.isError)
        assertNull(ok.error)
        assertEquals(9, ok.dataUpdatedAt)
        assertTrue(ok.isFetched)
    }

    @Test fun aFailureKeepsTheDataAndRecordsTheError() {
        val e = IllegalStateException("down")
        val state = succeededState("old", 3).fetching().failed(e)
        assertEquals("old", state.data)
        assertSame(e, state.error)
        assertTrue(state.isError)
        assertFalse(state.isFetching)
        assertFalse(state.isLoading)
        assertTrue(state.isFetched)
    }

    @Test fun aCancelledRequestOnlyClearsTheFlags() {
        val state = QueryState<Int>().fetching().notFetching()
        assertFalse(state.isFetching)
        assertFalse(state.isLoading)
        assertFalse(state.isFetched)
    }

    @Test fun seedingClearsAnErrorButDoesNotMarkItFetched() {
        val seeded = QueryState<Int>().failed(IllegalStateException("x")).copy(isFetched = false).seeded(4, 8)
        assertEquals(4, seeded.data)
        assertEquals(8, seeded.dataUpdatedAt)
        assertFalse(seeded.isError)
        assertNull(seeded.error)
        assertFalse(seeded.isFetched)
    }

    // ---- SingleFlight ------------------------------------------------------------

    @Test fun askersWhileOneRunsShareIt() = runTest {
        val flight = SingleFlight<Unit, Int>()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val answers = List(5) {
            async { flight.join(Unit, this) { runs++; release.await(); 42 }.await() }
        }
        testScheduler.runCurrent()
        release.complete(Unit)
        assertEquals(List(5) { 42 }, answers.awaitAll())
        assertEquals(1, runs)
    }

    @Test fun afterItFinishesTheNextAskerStartsANewOne() = runTest {
        val flight = SingleFlight<Unit, Int>()
        var runs = 0
        assertEquals(1, flight.join(Unit, this) { ++runs }.await())
        assertNull(flight.running)
        assertEquals(2, flight.join(Unit, this) { ++runs }.await())
    }

    @Test fun aRequestForAnotherKeyReplacesTheRunningOneWhichStillAnswersItsAwaiters() = runTest {
        val flight = SingleFlight<String, String>()
        val release = CompletableDeferred<Unit>()
        val a = flight.join("a", this) { release.await(); "A" }
        val b = flight.join("b", this) { "B" }
        assertSame(b, flight.running("b"))
        assertNull(flight.running("a"))
        release.complete(Unit)
        assertEquals("A", a.await())
        assertEquals("B", b.await())
    }

    @Test fun aReplacedRequestIsCancelledOnlyWhenAsked() = runTest {
        val flight = SingleFlight<String, String>()
        val hold = CompletableDeferred<Unit>()
        val a = flight.join("a", this) { hold.await(); "A" }
        flight.join("b", this, cancelReplaced = true) { "B" }
        testScheduler.runCurrent()
        assertTrue(a.isCancelled)
        hold.complete(Unit)
    }

    @Test fun cancelStopsTheRunningRequestAndForgetsIt() = runTest {
        val flight = SingleFlight<Unit, Int>()
        val hold = CompletableDeferred<Unit>()
        val job = flight.join(Unit, this) { hold.await(); 1 }
        flight.cancel()
        assertTrue(job.isCancelled)
        assertNull(flight.running)
        assertFailsWith<kotlinx.coroutines.CancellationException> { job.await() }
    }

    @Test fun aRequestKnowsItselfAsTheCurrentOneUntilReplacedOrCancelled() = runTest {
        val flight = SingleFlight<Unit, Boolean>()
        val asked = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        var sawItself: Boolean? = null
        val job = flight.join(Unit, this) {
            sawItself = flight.isCurrent(currentCoroutineContext()[Job])
            asked.complete(Unit)
            hold.await()
            true
        }
        asked.await()
        assertEquals(true, sawItself)
        assertTrue(flight.isCurrent(job))
        flight.cancel()
        assertFalse(flight.isCurrent(job))
        assertNotNull(job)
    }
}
