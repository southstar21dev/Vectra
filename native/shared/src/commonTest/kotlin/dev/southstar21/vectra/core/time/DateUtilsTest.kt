package dev.southstar21.vectra.core.time

import dev.southstar21.vectra.core.preferences.TimeFormat
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class DateUtilsTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilliseconds()

    @Test fun nextBudapestDayInWinter() {
        val now = nowInBudapest(Instant.parse("2026-01-15T23:30:00Z"))
        assertEquals("2026-01-16", now.ymd)
        assertEquals(5, now.dayOfWeek)
        assertEquals(0, now.hours)
        assertEquals(30, now.minutes)
    }

    @Test fun summerTime() {
        val now = nowInBudapest(Instant.parse("2026-07-01T22:30:00Z"))
        assertEquals("2026-07-02", now.ymd)
        assertEquals(0, now.hours)
        assertEquals(30, now.minutes)
    }

    @Test fun weekdayOfBudapestDate() {
        val now = nowInBudapest(Instant.parse("2026-01-18T23:00:00Z"))
        assertEquals("2026-01-19", now.ymd)
        assertEquals(1, now.dayOfWeek)
    }

    @Test fun defaultShape() {
        val now = nowInBudapest()
        assertTrue(now.year > 2020)
        assertTrue(now.month in 1..12)
        assertTrue(now.dayOfWeek in 0..6)
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}$""").matches(now.ymd))
    }

    @Test fun formatHHMMPads() {
        assertEquals("08:00", formatHHMM("8:00"))
        assertEquals("08:05", formatHHMM("8:5"))
        assertEquals("14:30", formatHHMM("14:30"))
        assertEquals("08:00", formatHHMM("08:00:00"))
        assertEquals("15:45", formatHHMM("15:45:30"))
        assertEquals("", formatHHMM(null))
        assertEquals("", formatHHMM(""))
    }

    @Test fun formatTime24() {
        assertEquals("08:00", formatTime("08:00"))
        assertEquals("08:15", formatTime("8:15"))
        assertEquals("13:45", formatTime("13:45"))
        assertEquals("00:00", formatTime("00:00"))
        assertEquals("23:59", formatTime("23:59"))
        assertEquals("09:30", formatTime("09:30", TimeFormat.H24))
        assertEquals("16:20", formatTime("16:20", TimeFormat.H24))
    }

    @Test fun formatTime12() {
        assertEquals("8:00 AM", formatTime("08:00", TimeFormat.H12))
        assertEquals("8:05 AM", formatTime("8:05", TimeFormat.H12))
        assertEquals("12:00 PM", formatTime("12:00", TimeFormat.H12))
        assertEquals("1:45 PM", formatTime("13:45", TimeFormat.H12))
        assertEquals("12:00 AM", formatTime("00:00", TimeFormat.H12))
        assertEquals("11:59 PM", formatTime("23:59", TimeFormat.H12))
    }

    @Test fun formatTimeEmpty() {
        assertEquals("", formatTime(null))
        assertEquals("", formatTime(""))
        assertEquals("", formatTime(null, TimeFormat.H12))
    }

    @Test fun budapestHm() = assertEquals("06:03", formatBudapestHm(ms("2026-01-15T05:03:00Z")))

    @Test fun formatClockFormats() {
        val afternoon = ms("2026-09-07T12:30:00Z")
        val morning = ms("2026-09-07T06:05:00Z")
        assertEquals("14:30", formatClock(afternoon))
        assertEquals("08:05", formatClock(morning))
        assertEquals("2:30 PM", formatClock(afternoon, TimeFormat.H12))
        assertEquals("8:05 AM", formatClock(morning, TimeFormat.H12))
    }

    @Test fun dayNames() {
        assertEquals("Monday", getDayName(1))
        assertEquals("Saturday", getDayName(6))
        assertEquals("", getDayName(9))
        assertEquals("Sun", getShortDayName(0))
        assertEquals("Fri", getShortDayName(5))
    }

    @Test fun ymdZeroPads() = assertEquals("2026-01-05", LocalDate(2026, 1, 5).ymd())

    @Test fun formatDDMMYYYYFormats() {
        assertEquals("05/01/2026", formatDDMMYYYY("2026-01-05"))
        assertEquals("", formatDDMMYYYY(null))
        assertEquals("not-a-date", formatDDMMYYYY("not-a-date"))
    }

    @Test fun validCalendarDate() {
        assertTrue(isValidCalendarDate("2026-02-28"))
        assertTrue(isValidCalendarDate("2024-02-29"))
        assertFalse(isValidCalendarDate("2025-02-29"))
        assertFalse(isValidCalendarDate("2026-02-31"))
        assertFalse(isValidCalendarDate("2026-2-3"))
    }

    @Test fun ymdGuard() {
        assertTrue(isYmdString("2026-02-31"))
        assertFalse(isYmdString(null))
        assertFalse(isYmdString("2026-2-3"))
    }

    @Test fun minuteOfDay() {
        assertEquals("00:00", formatMinuteOfDay(0))
        assertEquals("08:05", formatMinuteOfDay(485))
        assertEquals("23:59", formatMinuteOfDay(1439))
        assertEquals("24:00", formatMinuteOfDay(1440))
        assertEquals("00:00", formatMinuteOfDay(-5))
    }

    @Test fun dayMonth() {
        assertEquals("12 October", formatDayMonth("2026-10-12"))
        assertEquals("1 January", formatDayMonth("2026-01-01"))
        assertEquals("not a date", formatDayMonth("not a date"))
    }
}
