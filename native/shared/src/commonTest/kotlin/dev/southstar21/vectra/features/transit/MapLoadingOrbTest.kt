package dev.southstar21.vectra.features.transit

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which stretches of the projected route the loading orb's dots spread over. */
class MapLoadingOrbTest {
    @Test fun wholeLineInsideIsOneRun() {
        val line = listOf(Offset(10f, 10f), Offset(50f, 20f), Offset(90f, 90f))
        assertEquals(listOf(line), visibleRuns(line, 100f, 100f))
    }

    @Test fun cutAtTheEdges() {
        val runs = visibleRuns(listOf(Offset(-50f, 50f), Offset(150f, 50f)), 100f, 100f)
        assertEquals(listOf(listOf(Offset(0f, 50f), Offset(100f, 50f))), runs)
    }

    @Test fun everyStretchInViewCounts() {
        // Across the top, out of view to the right, back across the bottom.
        val line = listOf(Offset(10f, 20f), Offset(200f, 20f), Offset(200f, 80f), Offset(10f, 80f))
        val runs = visibleRuns(line, 100f, 100f)
        assertEquals(2, runs.size)
        assertEquals(listOf(Offset(10f, 20f), Offset(100f, 20f)), runs[0])
        assertEquals(listOf(Offset(100f, 80f), Offset(10f, 80f)), runs[1])
    }

    @Test fun nothingInViewGivesNoRuns() {
        assertTrue(visibleRuns(listOf(Offset(-5f, -5f), Offset(-50f, 50f)), 100f, 100f).isEmpty())
        assertTrue(visibleRuns(listOf(Offset(5f, 5f)), 100f, 100f).isEmpty())
        assertTrue(visibleRuns(emptyList(), 100f, 100f).isEmpty())
    }
}
