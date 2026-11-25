package dev.southstar21.vectra.features.reminders

import dev.southstar21.vectra.features.schedule.ScheduleItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate

class OvernightSessionsTest {
    private fun shift(start: String, end: String) =
        ScheduleItem("11111111-1111-4111-8111-111111111111", ScheduleItem.TYPE_SHIFT, "Night", "2026-11-27", start, end)

    private fun sessions(item: ScheduleItem) =
        expandSessions(emptyList(), listOf(item), includeItems = true, from = LocalDate.parse("2026-11-23"), days = 14)

    @Test fun overnightShiftEndsNextMorning() {
        val s = sessions(shift("22:00", "06:00")).single()
        assertEquals("2026-11-27", s.day)
        assertEquals(8 * 60 * MinuteMs, s.endMs - s.startMs)
    }

    @Test fun shiftEndingAtMidnightIsKept() {
        val s = sessions(shift("18:00", "00:00")).single()
        assertEquals(6 * 60 * MinuteMs, s.endMs - s.startMs)
    }

    @Test fun daytimeShiftUnchanged() {
        val s = sessions(shift("09:00", "12:00")).single()
        assertEquals(3 * 60 * MinuteMs, s.endMs - s.startMs)
    }
}
