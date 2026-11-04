package dev.southstar21.vectra.features.transit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Fetchers and clock are injected; runs on virtual time. */
class ArrivalsLoaderTest {
    private fun response(stopId: String) =
        FutarArrivalsResponse(status = "OK", data = FutarArrivalsData(entry = FutarArrivalsEntry(stopId = stopId, stopTimes = emptyList())))

    private class Calls {
        val single = mutableListOf<Triple<String, Int, Int?>>()
        val batch = mutableListOf<Pair<List<String>, Int>>()
    }

    private fun TestScope.loader(
        calls: Calls,
        single: suspend (String, Int, Int?) -> FutarArrivalsResponse = { id, _, _ -> response(id) },
        batch: suspend (List<String>, Int) -> StopArrivalsBatch = { _, _ -> error("unexpected batch") },
    ) = ArrivalsLoader(
        // Like the repository's SupervisorJob scope: a failed fetch must not cancel siblings.
        scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job)),
        fetchSingle = { id, m, l -> calls.single += Triple(id, m, l); single(id, m, l) },
        fetchBatch = { ids, m -> calls.batch += ids to m; batch(ids, m) },
        clock = { testScheduler.currentTime },
    )

    @Test fun coalescesOneWindowIntoOneBatch() = runTest {
        val calls = Calls()
        val l = loader(calls, batch = { _, _ -> StopArrivalsBatch(mapOf("BKK_CS1" to response("BKK_CS1"), "BKK_CS2" to response("BKK_CS2")), emptyMap()) })
        val a = async { l.load("BKK_CS1") }
        val b = async { l.load("BKK_CS2") }
        val a2 = async { l.load("BKK_CS1") }
        runCurrent()
        advanceTimeBy(BatchWindowMs + 1)
        assertEquals(response("BKK_CS1"), a.await())
        assertEquals(response("BKK_CS1"), a2.await())
        assertEquals(response("BKK_CS2"), b.await())
        assertEquals(listOf(listOf("BKK_CS1", "BKK_CS2") to 90), calls.batch)
        assertTrue(calls.single.isEmpty())
    }

    @Test fun rejectsOnlyUnansweredStops() = runTest {
        val calls = Calls()
        val l = loader(calls, batch = { _, _ -> StopArrivalsBatch(mapOf("BKK_CS1" to response("BKK_CS1")), mapOf("BKK_CS2" to "upstream down")) })
        val a = async { runCatching { l.load("BKK_CS1") } }
        val b = async { runCatching { l.load("BKK_CS2") } }
        runCurrent()
        advanceTimeBy(BatchWindowMs + 1)
        assertTrue(a.await().isSuccess)
        assertEquals("upstream down", b.await().exceptionOrNull()?.message)
    }

    @Test fun singleStopUsesSingleEndpoint() = runTest {
        val calls = Calls()
        val l = loader(calls)
        val a = async { l.load("BKK_CS1") }
        runCurrent()
        advanceTimeBy(BatchWindowMs + 1)
        assertEquals(response("BKK_CS1"), a.await())
        assertEquals(listOf(Triple("BKK_CS1", 90, null as Int?)), calls.single)
        assertTrue(calls.batch.isEmpty())
    }

    @Test fun shallowReusesFreshDeep() = runTest {
        val calls = Calls()
        val l = loader(calls)
        val deep = l.loadWithLimit("BKK_CS1", 200)
        runCurrent()
        assertEquals(listOf(Triple("BKK_CS1", 90, 200 as Int?)), calls.single)
        assertSame(deep, l.load("BKK_CS1"))
        assertEquals(1, calls.single.size)

        advanceTimeBy(DeepReuseMs + 1)
        val shallow = async { l.load("BKK_CS1") }
        runCurrent()
        advanceTimeBy(BatchWindowMs + 1)
        shallow.await()
        assertEquals(2, calls.single.size)
    }

    @Test fun neverReusesFailedDeep() = runTest {
        val calls = Calls()
        var first = true
        val l = loader(calls, single = { id, _, _ -> if (first) { first = false; error("nope") } else response(id) })
        assertEquals("nope", runCatching { l.loadWithLimit("BKK_CS1", 200) }.exceptionOrNull()?.message)
        runCurrent()
        val shallow = async { l.load("BKK_CS1") }
        runCurrent()
        advanceTimeBy(BatchWindowMs + 1)
        assertEquals(response("BKK_CS1"), shallow.await())
    }
}
