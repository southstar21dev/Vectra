package dev.southstar21.vectra.features.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScheduleBreakTest {
    @Test fun nullWhenMissingOrInvalid() {
        assertNull(calculateBreakInfo(null, "10:00"))
        assertNull(calculateBreakInfo("09:00", null))
        assertNull(calculateBreakInfo("", ""))
        assertNull(calculateBreakInfo("invalid", "10:00"))
    }

    @Test fun nullWhenNoGap() {
        assertNull(calculateBreakInfo("10:00", "10:00"))
        assertNull(calculateBreakInfo("10:30", "10:00"))
    }

    @Test fun hoursAndMinutes() {
        val b = assertNotNull(calculateBreakInfo("09:30", "17:20"))
        assertEquals(470, b.gapMinutes)
        assertEquals("7h 50m", b.durationLabel)
        assertEquals("09:30", b.startTime)
        assertEquals("17:20", b.endTime)
    }

    @Test fun wholeHours() {
        val b = assertNotNull(calculateBreakInfo("10:00", "12:00"))
        assertEquals(120, b.gapMinutes)
        assertEquals("2h", b.durationLabel)
    }

    @Test fun minutesOnly() {
        val b = assertNotNull(calculateBreakInfo("10:00", "10:45"))
        assertEquals(45, b.gapMinutes)
        assertEquals("45m", b.durationLabel)
    }
}
