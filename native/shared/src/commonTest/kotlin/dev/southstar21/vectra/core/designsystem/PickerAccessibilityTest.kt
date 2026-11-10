package dev.southstar21.vectra.core.designsystem

import dev.southstar21.vectra.core.designsystem.components.dayCellDescription
import dev.southstar21.vectra.core.designsystem.components.wheelIndexFor
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class PickerAccessibilityTest {
    @Test
    fun calendarDaySaysTheFullDate() {
        assertEquals("Friday, 30 October 2026", dayCellDescription(LocalDate(2026, 10, 30), isToday = false))
        assertEquals("Monday, 2 November 2026, today", dayCellDescription(LocalDate(2026, 11, 2), isToday = true))
    }

    @Test
    fun wheelAdjustLandsOnARowInRange() {
        assertEquals(5, wheelIndexFor(5.4f, 24))
        assertEquals(6, wheelIndexFor(5.6f, 24))
        assertEquals(0, wheelIndexFor(-1f, 24))
        assertEquals(23, wheelIndexFor(30f, 24))
        assertEquals(1, wheelIndexFor(1f, 2))
    }
}
