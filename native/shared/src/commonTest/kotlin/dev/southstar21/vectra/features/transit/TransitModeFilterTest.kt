package dev.southstar21.vectra.features.transit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransitModeFilterTest {
    private fun prefs(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test fun readsArrayAndUppercases() = assertEquals(listOf("TRAM", "BUS"), parseModeFilter(prefs("""{"transit_mode_filter":["tram","BUS"]}""")))
    @Test fun dropsNonStrings() = assertEquals(listOf("TRAM"), parseModeFilter(prefs("""{"transit_mode_filter":["TRAM",3,null]}""")))
    @Test fun readsLegacyString() = assertEquals(listOf("TRAM"), parseModeFilter(prefs("""{"transit_mode_filter":"tram"}""")))

    @Test fun legacyAllAndMissing() {
        assertEquals(emptyList(), parseModeFilter(prefs("""{"transit_mode_filter":"ALL"}""")))
        assertEquals(emptyList(), parseModeFilter(prefs("{}")))
        assertEquals(emptyList(), parseModeFilter(null))
    }

    @Test fun dropsAllEntries() {
        assertEquals(listOf("TRAM"), parseModeFilter(prefs("""{"transit_mode_filter":["TRAM","ALL"]}""")))
        assertEquals(emptyList(), parseModeFilter(prefs("""{"transit_mode_filter":["all"]}""")))
    }

    @Test fun singleReplaces() = assertEquals(listOf("TRAM"), selectSingleModeFilter(listOf("BUS"), "TRAM"))
    @Test fun singleTogglesOff() = assertEquals(emptyList(), selectSingleModeFilter(listOf("TRAM"), "TRAM"))
    @Test fun singleCollapses() = assertEquals(listOf("TRAM"), selectSingleModeFilter(listOf("BUS", "TRAM"), "TRAM"))

    @Test fun singleAllClears() {
        assertEquals(emptyList(), selectSingleModeFilter(listOf("TRAM"), "ALL"))
        assertEquals(emptyList(), selectSingleModeFilter(listOf("TRAM"), "all"))
    }

    @Test fun singleNormalizes() = assertEquals(listOf("TRAM"), selectSingleModeFilter(emptyList(), "tram"))

    @Test fun sameIsOrderInsensitive() {
        assertTrue(sameModeFilter(listOf("BUS", "TRAM"), listOf("TRAM", "BUS")))
        assertTrue(sameModeFilter(emptyList(), emptyList()))
        assertFalse(sameModeFilter(listOf("TRAM"), listOf("TRAM", "BUS")))
        assertFalse(sameModeFilter(listOf("TRAM"), listOf("BUS")))
    }

    @Test fun serializesWrites() = runTest {
        val calls = mutableListOf<List<String>>()
        val gate = CompletableDeferred<Unit>()
        val write: suspend (List<String>) -> Unit = { calls += it; gate.await() }
        val queue = createModeFilterWriteQueue()

        val first = async { queue(listOf("TRAM"), write) }
        runCurrent()
        assertEquals(listOf(listOf("TRAM")), calls)

        val second = async { queue(listOf("BUS"), write) }
        runCurrent()
        assertEquals(listOf(listOf("TRAM")), calls)

        gate.complete(Unit)
        first.await(); second.await()
        assertEquals(listOf(listOf("TRAM"), listOf("BUS")), calls)
    }

    @Test fun collapsesBurst() = runTest {
        val calls = mutableListOf<List<String>>()
        val gate = CompletableDeferred<Unit>()
        val write: suspend (List<String>) -> Unit = { calls += it; gate.await() }
        val queue = createModeFilterWriteQueue()
        val a = async { queue(listOf("TRAM"), write) }
        val b = async { queue(listOf("BUS"), write) }
        val c = async { queue(listOf("TRAM"), write) }
        runCurrent()
        gate.complete(Unit)
        a.await(); b.await(); c.await()
        assertEquals(listOf(listOf("TRAM")), calls)
    }

    @Test fun skipsRepeatOfLastWritten() = runTest {
        val calls = mutableListOf<List<String>>()
        val queue = createModeFilterWriteQueue()
        val write: suspend (List<String>) -> Unit = { calls += it }
        queue(listOf("TRAM"), write)
        queue(listOf("TRAM"), write)
        assertEquals(listOf(listOf("TRAM")), calls)
    }

    @Test fun survivesFailure() = runTest {
        val calls = mutableListOf<List<String>>()
        val failing = CompletableDeferred<Unit>()
        val queue = createModeFilterWriteQueue()
        val first = async { runCatching { queue(listOf("TRAM")) { calls += it; failing.await() } } }
        runCurrent()
        val second = async { queue(listOf("BUS")) { calls += it } }
        runCurrent()
        failing.completeExceptionally(IllegalStateException("network"))
        val err = first.await().exceptionOrNull()
        assertEquals("network", err?.message)
        second.await()
        assertEquals(listOf(listOf("TRAM"), listOf("BUS")), calls)
        assertFailsWith<IllegalStateException> { throw err!! }
    }
}

class ModeFilterBurstTest {
    @Test fun twoCallBurstWritesOnlyTheLast() = runTest {
        val calls = mutableListOf<List<String>>()
        val queue = createModeFilterWriteQueue()
        val write: suspend (List<String>) -> Unit = { calls += it }
        val a = async { queue(listOf("TRAM"), write) }
        val b = async { queue(listOf("BUS"), write) }
        a.await(); b.await()
        assertEquals(listOf(listOf("BUS")), calls)
    }
}
