package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.time.Budapest
import dev.southstar21.vectra.features.reminders.expandSessions
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 04 #5: odd/even weeks of classes without dates of their own (every manual class). */
class ScheduleParityTest {
    /** A manual class as the editor saves it: no semester_start/semester_end. */
    private fun manual(frequency: String, semester: String = "2026/27/1", id: String = "manual") = scheduleEvent(
        id = id, source = "manual", courseName = "Manual $id", courseCode = "", semester = semester,
        semesterStart = null, semesterEnd = null, csvMetadata = freq(frequency),
    )

    private fun mondays(from: String, count: Int): List<String> =
        (0 until count).map { LocalDate.parse(from).plus(DatePeriod(days = 7 * it)).toString() }

    @Test fun aManualClassHasNoDatesOfItsOwnSoItUsesTheFallback() {
        val index = ScheduleEventIndex(listOf(manual("even")))
        assertEquals(null, index.earliestStart(manual("even")))
    }

    @Test fun evenWeeksAlternateAcrossAMonthBoundary() {
        // 2026/27/1 nominally starts in the week of 1 September: Monday 31 August is week one.
        val e = manual("even")
        val shown = mondays("2026-08-31", 10).map { classOccursOnDate(e, it) }
        assertEquals(listOf(false, true, false, true, false, true, false, true, false, true), shown)
        // The old first-of-month anchor restarted on 5 October and hid it; week six is even.
        assertTrue(classOccursOnDate(e, "2026-10-05"))
        assertFalse(classOccursOnDate(e, "2026-10-12"))
    }

    @Test fun oddAndEvenNeverMeetTheSameWeek() {
        val odd = manual("odd")
        val even = manual("even")
        for (day in mondays("2026-08-31", 16)) {
            assertTrue(classOccursOnDate(odd, day) != classOccursOnDate(even, day), day)
        }
    }

    @Test fun weeklyManualClassesShowEveryWeekOfTheSemester() {
        val e = manual("weekly")
        assertTrue(mondays("2026-08-31", 16).all { classOccursOnDate(e, it) })
    }

    @Test fun theSemestersFirstDatedClassSetsWeekOne() {
        // Neptun's first class of the semester meets on Wednesday 9 September: week one is that week.
        val neptun = scheduleEvent(id = "n", courseName = "Other", courseCode = "OTHER-G01", dayOfWeek = 3, semesterStart = "2026-09-09")
        val odd = manual("odd")
        val events = listOf(neptun, odd)
        assertTrue(classOccursOnDate(odd, "2026-09-07", events))
        assertFalse(classOccursOnDate(odd, "2026-09-14", events))
        assertTrue(classOccursOnDate(odd, "2026-09-21", events))
        assertFalse(classOccursOnDate(odd, "2026-08-31", events), "before the semester's first week")
    }

    @Test fun nominalSemesterStartsAreMondays() {
        assertEquals("2026-08-31", nominalSemesterStart("2026/27/1"))
        assertEquals("2027-02-01", nominalSemesterStart("2026/27/2"))
        assertEquals(null, nominalSemesterStart(""))
        assertEquals(null, nominalSemesterStart("autumn"))
    }

    @Test fun withoutASemesterTagParityIsStillStable() {
        val e = manual("odd", semester = "")
        // Week one is the fixed Monday 2024-01-01; 7 September 2026 is week 141.
        assertTrue(classOccursOnDate(e, "2026-09-07"))
        assertFalse(classOccursOnDate(e, "2026-09-14"))
        assertTrue(classOccursOnDate(e, "2026-09-21"))
    }

    @Test fun theScheduleTodayAndRemindersAgree() {
        val events = listOf(manual("even", id = "a"), manual("odd", id = "b"), manual("weekly", id = "c"))
        for (day in mondays("2026-08-31", 12)) {
            val shown = events.filter { classOccursOnDate(it, day, events) }.map { it.id }.toSet()
            val noon = LocalDateTime(LocalDate.parse(day), LocalTime(12, 0)).toInstant(Budapest)
            val today = scheduleTodayEvents(events, noon).map { it.id }.toSet()
            val reminded = expandSessions(events, emptyList(), includeItems = false, from = LocalDate.parse(day), days = 1)
                .map { it.key.substringBefore('@') }.toSet()
            assertEquals(shown, today, "today on $day")
            assertEquals(shown, reminded, "reminders on $day")
        }
    }
}
