package dev.southstar21.vectra.features.holidays

import dev.southstar21.vectra.core.time.toBudapestInstant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Midnight Budapest time on the given date ([month0] is 0-based). */
private fun budapest(year: Int, month0: Int, day: Int): Instant = LocalDateTime(year, month0 + 1, day, 0, 0).toBudapestInstant()

class HungarianHolidaysTest {
    @Test fun returns13HolidaysFor2026() = assertEquals(13, getHungarianHolidays(2026).size)

    @Test fun includesFixedHolidays() {
        val names = getHungarianHolidays(2026).map { it.name }
        listOf(
            "New Year's Day", "Revolution of 1848", "Labour Day", "State Foundation Day",
            "Revolution of 1956", "All Saints' Day", "Christmas Day", "Second Day of Christmas",
        ).forEach { assertTrue(it in names, it) }
    }

    @Test fun includesEasterBasedHolidays() {
        val names = getHungarianHolidays(2026).map { it.name }
        listOf("Good Friday", "Easter Sunday", "Easter Monday", "Whit Sunday", "Whit Monday").forEach { assertTrue(it in names, it) }
    }

    @Test fun computesEaster2026() {
        val easter = getHungarianHolidays(2026).first { it.name == "Easter Sunday" }
        assertEquals(Holiday(4, 5, "Easter Sunday", "Húsvétvasárnap"), easter)
    }

    @Test fun goodFridayIsEasterMinus2() {
        val gf = getHungarianHolidays(2026).first { it.name == "Good Friday" }
        assertEquals(4, gf.month); assertEquals(3, gf.day)
    }

    @Test fun whitSundayIsEasterPlus49() {
        val ws = getHungarianHolidays(2026).first { it.name == "Whit Sunday" }
        assertEquals(5, ws.month); assertEquals(24, ws.day)
    }

    @Test fun everyHolidayHasBothNames() = getHungarianHolidays(2026).forEach {
        assertTrue(it.name.isNotEmpty()); assertTrue(it.hungarian.isNotEmpty())
    }

    @Test fun todayIsAHoliday() {
        val r = assertNotNull(getNextHoliday(budapest(2026, 0, 1)))
        assertEquals("New Year's Day", r.holiday.name); assertEquals(0, r.daysUntil)
    }

    @Test fun nextWithin14Days() {
        val r = assertNotNull(getNextHoliday(budapest(2026, 2, 5)))
        assertEquals("Revolution of 1848", r.holiday.name); assertEquals(10, r.daysUntil)
    }

    @Test fun nullBeyond14Days() = assertNull(getNextHoliday(budapest(2026, 1, 10)))

    @Test fun yearBoundary() {
        val r = assertNotNull(getNextHoliday(budapest(2026, 11, 27)))
        assertEquals("New Year's Day", r.holiday.name); assertEquals(5, r.daysUntil)
    }

    @Test fun christmasToday() {
        val r = assertNotNull(getNextHoliday(budapest(2026, 11, 25)))
        assertEquals("Christmas Day", r.holiday.name); assertEquals(0, r.daysUntil)
    }

    @Test fun tomorrow() {
        val r = assertNotNull(getNextHoliday(budapest(2026, 11, 24)))
        assertEquals("Christmas Day", r.holiday.name); assertEquals(1, r.daysUntil)
    }

    @Test fun goodFriday2026() {
        val r = assertNotNull(getNextHoliday(budapest(2026, 2, 25)))
        assertEquals("Good Friday", r.holiday.name); assertEquals(4, r.holiday.month); assertEquals(3, r.holiday.day); assertEquals(9, r.daysUntil)
    }

    @Test fun easterAndPentecostTransitions() {
        getNextHoliday(budapest(2026, 3, 4)).let { assertEquals("Easter Sunday", it?.holiday?.name); assertEquals(1, it?.daysUntil) }
        getNextHoliday(budapest(2026, 3, 6)).let { assertEquals("Easter Monday", it?.holiday?.name); assertEquals(0, it?.daysUntil) }
        getNextHoliday(budapest(2026, 3, 20)).let { assertEquals("Labour Day", it?.holiday?.name); assertEquals(11, it?.daysUntil) }
        getNextHoliday(budapest(2026, 4, 15)).let { assertEquals("Whit Sunday", it?.holiday?.name); assertEquals(9, it?.daysUntil) }
    }

    @Test fun multipleWithinEasterSeason() {
        val r = getUpcomingHolidays(budapest(2026, 3, 2), 14)
        assertEquals(listOf("Good Friday", "Easter Sunday", "Easter Monday"), r.map { it.holiday.name })
    }

    @Test fun christmasToNewYear() {
        val r = getUpcomingHolidays(budapest(2026, 11, 20), 14)
        assertEquals(listOf("Christmas Day", "Second Day of Christmas", "New Year's Day"), r.map { it.holiday.name })
    }

    @Test fun countsFromBudapestDate() {
        // 23:30 UTC on Oct 22 is already Oct 23 in Budapest.
        val r = assertNotNull(getNextHoliday(Instant.parse("2026-10-22T23:30:00Z")))
        assertEquals(10, r.holiday.month); assertEquals(23, r.holiday.day); assertEquals(0, r.daysUntil)
    }

    @Test fun recognisesPublicHolidaysByYmd() {
        assertTrue(isHungarianPublicHoliday("2026-10-23"))
        assertTrue(isHungarianPublicHoliday("2026-04-06"))
        assertTrue(isHungarianPublicHoliday("2026-05-25"))
        assertFalse(isHungarianPublicHoliday("2026-10-22"))
        assertFalse(isHungarianPublicHoliday("not-a-date"))
    }

    @Test fun formatsHolidayDate() = assertEquals("Fri, Oct 23", formatHolidayDate(LocalDate(2026, 10, 23)))
}
