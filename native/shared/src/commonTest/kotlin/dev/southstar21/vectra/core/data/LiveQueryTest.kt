package dev.southstar21.vectra.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveQueryTest {
    private fun real(test: suspend () -> Unit) = runTest { withContext(Dispatchers.Default) { withTimeout(10_000) { test() } } }
    private fun appScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test fun aHealthyQueryKeepsItsInterval() = assertEquals(20_000, pollIntervalMs(20_000, 0))

    @Test fun failuresDoubleTheWait() {
        assertEquals(40_000, pollIntervalMs(20_000, 1))
        assertEquals(80_000, pollIntervalMs(20_000, 2))
    }

    @Test fun backoffIsCapped() {
        assertEquals(MaxPollBackoffMs, pollIntervalMs(20_000, 5))
        assertEquals(MaxPollBackoffMs, pollIntervalMs(20_000, 500))
    }

    @Test fun anIntervalAboveTheCapIsKept() = assertEquals(600_000, pollIntervalMs(600_000, 3))

    @Test fun idleEntriesAreEvictedOldestFirst() = real {
        val q = LiveQuery<Int, String>(appScope(), maxIdleEntries = 2) { "v$it" }
        q.fetch(1)
        q.fetch(2)
        q.fetch(3)
        assertNull(q.peek(1).data)
        assertEquals("v2", q.peek(2).data)
        assertEquals("v3", q.peek(3).data)
    }

    @Test fun usingAnEntryKeepsItAlive() = real {
        val q = LiveQuery<Int, String>(appScope(), maxIdleEntries = 2) { "v$it" }
        q.fetch(1)
        q.fetch(2)
        q.fetch(1)
        q.fetch(3)
        assertNotNull(q.peek(1).data)
        assertNull(q.peek(2).data)
    }

    @Test fun observedEntriesSurvive() = real {
        val scope = appScope()
        val q = LiveQuery<Int, String>(scope, maxIdleEntries = 1) { "v$it" }
        val job = scope.launch { q.observe(1).collect { } }
        q.observe(1).first { it.data != null }
        q.fetch(2)
        q.fetch(3)
        q.fetch(4)
        assertEquals("v1", q.peek(1).data)
        job.cancel()
    }

    @Test fun withoutACapNothingIsEvicted() = real {
        val q = LiveQuery<Int, String>(appScope()) { "v$it" }
        repeat(50) { q.fetch(it) }
        assertEquals("v0", q.peek(0).data)
    }

    @Test fun pollingPausesOfflineAndRefetchesOnReconnect() = real {
        val scope = appScope()
        val online = MutableStateFlow(true)
        var fetches = 0
        var time = 0L
        val q = LiveQuery<Int, Int>(scope, refetchIntervalMs = 2_000, staleTimeMs = 0, clock = { time }, online = online) { ++fetches }
        val job = scope.launch { q.observe(1).collect { } }
        q.observe(1).first { it.data != null }
        online.value = false
        delay(50)
        val whileOffline = fetches
        time += 60_000
        online.value = true
        q.observe(1).first { (it.data ?: 0) > whileOffline }
        assertTrue(fetches > whileOffline)
        job.cancel()
    }

    @Test fun aFailureIsStillRetriedThenReported() = real {
        var calls = 0
        val q = LiveQuery<Int, String>(appScope(), retry = 0) { calls++; error("down") }
        val failure = runCatching { q.fetch(1) }.exceptionOrNull()
        assertEquals("down", failure?.message)
        assertTrue(q.peek(1).isError)
        assertEquals(1, calls)
    }

    @Test fun concurrentFetchesShareOneRequest() = real {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val q = LiveQuery<Int, String>(appScope()) { calls++; gate.await(); "v$it" }
        val results = coroutineScope {
            val all = (1..5).map { async { q.fetch(1) } }
            while (calls == 0) delay(1)
            // Let the other four reach the query and join the request before it settles; on a slow
            // runner one arriving after it finished would rightly start a second request.
            delay(200)
            gate.complete(Unit)
            all.awaitAll()
        }
        assertEquals(List(5) { "v1" }, results)
        assertEquals(1, calls)
    }

    @Test fun ensureServesFreshDataAndRefetchesStaleData() = real {
        var time = 1_000L
        var calls = 0
        val q = LiveQuery<Int, Int>(appScope(), staleTimeMs = 100, clock = { time }) { ++calls }
        assertEquals(1, q.ensure(1))
        time += 99
        assertEquals(1, q.ensure(1))
        time += 1
        assertEquals(2, q.ensure(1))
    }

    @Test fun aRetriedFailureNeverSurfaces() = real {
        var calls = 0
        val q = LiveQuery<Int, String>(appScope(), retry = 1) { if (++calls == 1) error("blip") else "ok" }
        assertEquals("ok", q.fetch(1))
        assertEquals(2, calls)
        assertFalse(q.peek(1).isError)
        assertFalse(q.peek(1).isFetching)
    }

    @Test fun aFailedRefetchKeepsTheLastGoodData() = real {
        var fail = false
        val q = LiveQuery<Int, String>(appScope(), retry = 0) { if (fail) error("down") else "good" }
        q.fetch(1)
        fail = true
        assertFails { q.fetch(1) }
        val s = q.peek(1)
        assertEquals("good", s.data)
        assertTrue(s.isError)
        assertFalse(s.isFetching)
        assertFalse(s.isLoading)
    }

    @Test fun pollingStopsWithTheLastObserver() = real {
        val scope = appScope()
        var calls = 0
        val q = LiveQuery<Int, Int>(scope, refetchIntervalMs = 1_000) { ++calls }
        val job = scope.launch { q.observe(1).collect { } }
        while (calls < 2) delay(5)
        job.cancelAndJoin()
        val atRelease = calls
        delay(1_300)
        assertEquals(atRelease, calls)
    }

    @Test fun seededDataIsVisibleAndAPlaceholderNeverOverwrites() = real {
        val q = LiveQuery<Int, String>(appScope()) { "fetched" }
        q.seedPlaceholder(1, "placeholder")
        waitFor { q.peek(1).data == "placeholder" }
        assertFalse(q.peek(1).isFetched)
        q.setData(1, "set")
        waitFor { q.peek(1).data == "set" }
        q.seedPlaceholder(1, "ignored")
        q.setData(2, "marker")
        waitFor { q.peek(2).data == "marker" }
        assertEquals("set", q.peek(1).data)
    }

    @Test fun invalidateRefetchesOnlyWhatIsObserved() = real {
        val scope = appScope()
        val calls = HashMap<Int, Int>()
        val q = LiveQuery<Int, Int>(scope, staleTimeMs = 60_000) { k -> (calls.getOrElse(k) { 0 } + 1).also { calls[k] = it } }
        q.fetch(1)
        val job = scope.launch { q.observe(2).collect { } }
        waitFor { q.peek(2).data == 1 }
        q.invalidate(1)
        q.invalidate(2)
        waitFor { q.peek(2).data == 2 }
        assertEquals(1, calls[1])
        // The unobserved entry is only marked: its next ensure ignores the staleTime.
        assertEquals(2, q.ensure(1))
        job.cancel()
    }

    @Test fun anAwaiterLeavingDoesNotCancelTheSharedFetch() = real {
        val gate = CompletableDeferred<Unit>()
        var started = false
        val q = LiveQuery<Int, String>(appScope()) { started = true; gate.await(); "landed" }
        coroutineScope {
            val leaver = launch { q.fetch(1) }
            while (!started) delay(1)
            leaver.cancelAndJoin()
            gate.complete(Unit)
        }
        waitFor { q.peek(1).data == "landed" }
        assertFalse(q.peek(1).isFetching)
    }

    @Test fun clearStopsAFetchWhoseAwaiterAlreadyLeft() = real {
        val gate = CompletableDeferred<Unit>()
        var started = false
        var cancelled = false
        val q = LiveQuery<Int, String>(appScope()) {
            started = true
            try {
                gate.await()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
            "previous user"
        }
        coroutineScope {
            val leaver = launch { q.fetch(1) }
            while (!started) delay(1)
            leaver.cancelAndJoin()
        }
        q.clear()
        waitFor { cancelled }
        gate.complete(Unit)
        delay(20)
        assertEquals(QueryState(), q.peek(1))
    }

    @Test fun aClearedFetchDoesNotBlockTheNextOne() = real {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val q = LiveQuery<Int, String>(appScope()) { if (++calls == 1) gate.await(); "v$calls" }
        val first = appScope().launch { runCatching { q.fetch(1) } }
        while (calls == 0) delay(1)
        q.clear()
        first.join()
        waitFor { q.peek(1) == QueryState<String>() }
        assertEquals("v2", q.fetch(1))
        assertFalse(q.peek(1).isFetching)
    }

    @Test fun anEntryWhoseFetchOutlivedItsAwaiterIsNotEvicted() = real {
        val gate = CompletableDeferred<Unit>()
        var started = false
        val q = LiveQuery<Int, String>(appScope(), maxIdleEntries = 1) { k ->
            if (k == 1) { started = true; gate.await() }
            "v$k"
        }
        coroutineScope {
            val leaver = launch { q.fetch(1) }
            while (!started) delay(1)
            leaver.cancelAndJoin()
        }
        q.fetch(2)
        q.fetch(3)
        gate.complete(Unit)
        waitFor { q.peek(1).data == "v1" }
    }

    @Test fun pollingSurvivesAClearCancellingTheFetchItAwaited() = real {
        val scope = appScope()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val q = LiveQuery<Int, Int>(scope, refetchIntervalMs = 1_000) { if (++calls == 1) gate.await(); calls }
        val job = scope.launch { q.observe(1).collect { } }
        while (calls == 0) delay(1)
        q.clear()
        waitFor { (q.peek(1).data ?: 0) >= 2 }
        job.cancel()
    }

    @Test fun peekIsSafeWhileEntriesChange() = real {
        val q = LiveQuery<Int, Int>(appScope(), maxIdleEntries = 8) { it }
        coroutineScope {
            val reader = launch(Dispatchers.Default) { while (isActive) repeat(500) { q.peek(it) } }
            repeat(500) { q.fetch(it) }
            reader.cancel()
        }
        assertEquals(499, q.peek(499).data)
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        while (!condition()) delay(2)
    }
}
