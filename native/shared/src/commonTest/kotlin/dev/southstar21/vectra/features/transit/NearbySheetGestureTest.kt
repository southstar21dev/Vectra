package dev.southstar21.vectra.features.transit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Sheet gesture maths, plus groupByLine from NearbyLines. */
class NearbySheetGestureTest {
    private fun pull(deltaY: Double, sheetY: Double = 0.0, feedScrollTop: Double? = 0.0) =
        shouldPullSheet(sheetY = sheetY, deltaY = deltaY, feedScrollTop = feedScrollTop, threshold = 6.0)

    @Test fun takesOverAtTop() = assertTrue(pull(7.0))
    @Test fun ignoresWithinThreshold() = assertFalse(pull(6.0))
    @Test fun leavesUpwardDrags() = assertFalse(pull(-7.0))
    @Test fun leavesScrolledList() = assertFalse(pull(7.0, feedScrollTop = 120.0))
    @Test fun takesOverOutsideList() = assertTrue(pull(7.0, feedScrollTop = null))
    @Test fun noPullWhenPeeked() = assertFalse(pull(7.0, sheetY = 40.0))

    @Test fun suppressesExactlyOneClick() {
        val s = createClickSuppressor()
        s.arm()
        assertTrue(s.consume())
        assertFalse(s.consume())
    }

    @Test fun notArmedWithoutDrag() = assertFalse(createClickSuppressor().consume())

    @Test fun newPressDropsPending() {
        val s = createClickSuppressor()
        s.arm()
        s.beginPress()
        assertFalse(s.consume())
    }

    private fun item(stopId: String, routeId: String, headsign: String) = RadarDepartureItem(
        id = "$stopId-$routeId-$headsign", stopId = stopId, stopName = "S", distanceMeters = 0.0, walkMinutes = 1,
        feasibility = Feasibility.Catchable, routeId = routeId, shortName = routeId, routeType = "BUS", color = "000000",
        textColor = "FFFFFF", headsign = headsign, arrivalMs = 0, countdownMinutes = 1, isPredicted = true,
    )

    @Test fun groupsByLine() {
        val groups = groupByLine(
            listOf(
                item("s", "a", "X"), item("s", "a", "X"), item("s", "a", "Y"), item("s", "a", "Z"),
                item("o", "b", "X"), item("s", "b", "X"), item("s", "c", "X"),
            ),
            "s",
        )
        assertEquals(listOf("a", "b"), groups.map { it.routeId })
        assertEquals(listOf("X", "Y"), groups[0].directions.map { it.headsign })
        assertEquals(listOf("X"), groups[1].directions.map { it.headsign })
    }
}
