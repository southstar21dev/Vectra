package dev.southstar21.vectra.features.schedule

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class WeekNavigationTest {
    private val today = "2026-10-29"
    private val september = getWeeksInMonth(LocalDate(2025, 9, 1), today)
    private val october = getWeeksInMonth(LocalDate(2025, 10, 1), today)

    @Test fun forwardPastSharedWeek() {
        val last = september.last()
        assertEquals("2025-09-29", last.startStr)
        assertEquals("2025-09-29", october[0].startStr)
        assertEquals("2025-10-06", october[resolveAdjacentWeekIndex(october, "after", last.startStr)].startStr)
    }

    @Test fun backPastSharedWeek() =
        assertEquals("2025-09-22", september[resolveAdjacentWeekIndex(september, "before", october[0].startStr)].startStr)

    @Test fun firstLastWhenNotShared() {
        val december = getWeeksInMonth(LocalDate(2025, 12, 1), today)
        val january = getWeeksInMonth(LocalDate(2026, 1, 1), today)
        assertEquals("2026-01-05", january[resolveAdjacentWeekIndex(january, "after", december.last().startStr)].startStr)
        assertEquals(0, resolveAdjacentWeekIndex(emptyList(), "after", "2026-01-01"))
    }

    @Test fun keepsNavigatedWeek() {
        assertEquals(3, resolveWeekIndexAfterRecompute(false, 3, 1, 1))
        assertEquals(3, resolveWeekIndexAfterRecompute(false, 3, 1, 2))
    }

    @Test fun followsCurrentWeek() {
        assertEquals(2, resolveWeekIndexAfterRecompute(false, 1, 1, 2))
        assertEquals(2, resolveWeekIndexAfterRecompute(false, null, 1, 2))
    }

    @Test fun newContextDefault() = assertEquals(0, resolveWeekIndexAfterRecompute(true, 3, 1, 0))
}
