package dev.southstar21.vectra.features.coursetracker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudWriteGateTest {
    @Test fun aCollapsedCallerSeesTheFailureOfTheRunThatReplacedIt() = runTest {
        val gate = CloudWriteGate()
        val release = CompletableDeferred<Unit>()
        val ran = mutableListOf<String>()
        val first = async { gate.enqueue { release.await(); ran += "first" } }
        runCurrent()
        val second = async { runCatching { gate.enqueue { ran += "second" } } }
        val third = async { runCatching { gate.enqueue { ran += "third"; error("network down") } } }
        runCurrent()
        release.complete(Unit)
        runCurrent()
        first.await()
        // The second caller's upload was replaced by the third; both see the third's failure.
        assertTrue(second.await().exceptionOrNull() is IllegalStateException)
        assertTrue(third.await().exceptionOrNull() is IllegalStateException)
        assertEquals(listOf("first", "third"), ran)
    }

    @Test fun collapsedCallersAllSucceedTogether() = runTest {
        val gate = CloudWriteGate()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val first = async { gate.enqueue { release.await(); runs++ } }
        runCurrent()
        val rest = List(3) { async { gate.enqueue { runs++ } } }
        runCurrent()
        release.complete(Unit)
        runCurrent()
        first.await()
        rest.forEach { it.await() }
        assertTrue(runs == 2)
    }
}
