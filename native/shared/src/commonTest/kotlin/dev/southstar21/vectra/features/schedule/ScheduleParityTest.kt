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

    /** The test account's autumn: an intensive course 7–10 September, term from Monday 14 September. */
    private val intensive = scheduleEvent(
        id = "intensive", courseName = "Organisational interpersonal skills development", courseCode = "OISD-G01",
        semesterStart = "2026-09-07", semesterEnd = "2026-09-10",
    )
    private val term = scheduleEvent(
        id = "term", courseName = "Financial Markets", courseCode = "FM-G05", semesterStart = "2026-09-14", semesterEnd = "2026-12-18",
    )

    /** The header's week number for the week starting on [monday] (computeAcademicWeekInfo, as the schedule shows it). */
    private fun headerWeek(events: List<ScheduleEvent>, monday: String, semester: String = "2026/27/1"): Int {
        val mon = LocalDate.parse(monday)
        val week = MonthWeek(0, mon, monday, mon.plus(DatePeriod(days = 6)).toString(), "", false)
        return computeAcademicWeekInfo(events, semester, monday, week)!!.weekNumber
    }

    @Test fun evenWeeksAreTheWeeksTheHeaderLabelsEvenWhenAnIntensiveWeekComesFirst() {
        val even = manual("even")
        val odd = manual("odd", id = "odd")
        val events = listOf(intensive, term, even, odd)
        assertEquals(1, headerWeek(events, "2026-09-14"), "the intensive week is not week one")
        for (day in mondays("2026-09-14", 16)) {
            val headerEven = headerWeek(events, day) % 2 == 0
            assertEquals(headerEven, classOccursOnDate(even, day, events), "even on $day")
            assertEquals(!headerEven, classOccursOnDate(odd, day, events), "odd on $day")
        }
        // The intensive week is "Intensive Week" (week 0) in the header: neither parity meets.
        assertFalse(classOccursOnDate(even, "2026-09-07", events))
        assertFalse(classOccursOnDate(odd, "2026-09-07", events))
    }

    @Test fun aCoursesOwnLaterStartDoesNotShiftItsParity() {
        // A Neptun course of the same name first meets in week two; the manual class still counts from week one.
        val late = scheduleEvent(id = "late", courseName = "Manual odd", courseCode = "", semesterStart = "2026-09-21")
        val odd = manual("odd", id = "odd")
        val events = listOf(intensive, term, late, odd)
        assertTrue(classOccursOnDate(odd, "2026-09-14", events), "week 1")
        assertFalse(classOccursOnDate(odd, "2026-09-21", events), "week 2")
        assertTrue(classOccursOnDate(odd, "2026-09-28", events), "week 3")
    }

    @Test fun aClassWithoutASemesterCountsInTheSemesterOfItsDate() {
        val spring = scheduleEvent(
            id = "spring", courseName = "Spring course", courseCode = "SP-G01", semester = "2026/27/2",
            semesterStart = "2027-02-08", semesterEnd = "2027-05-14",
        )
        val odd = manual("odd", semester = "")
        val events = listOf(intensive, term, spring, odd)
        assertTrue(classOccursOnDate(odd, "2026-09-14", events), "autumn week 1")
        assertFalse(classOccursOnDate(odd, "2026-09-21", events), "autumn week 2")
        assertEquals(1, headerWeek(events, "2027-02-08", "2026/27/2"))
        assertTrue(classOccursOnDate(odd, "2027-02-08", events), "spring week 1")
        assertFalse(classOccursOnDate(odd, "2027-02-15", events), "spring week 2")
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

    @Test fun withoutASemesterOrDatedClassesParityFollowsTheNominalStart() {
        val e = manual("odd", semester = "")
        // September 2026 belongs to 2026/27/1, nominally from the week of 1 September (Monday 31 August).
        assertTrue(classOccursOnDate(e, "2026-08-31"))
        assertFalse(classOccursOnDate(e, "2026-09-07"))
        assertTrue(classOccursOnDate(e, "2026-09-14"))
    }

    @Test fun anUnreadableSemesterTagFallsBackToAFixedMonday() {
        val e = manual("odd", semester = "autumn")
        // Week one is the fixed Monday 2024-01-01; 7 September 2026 is week 141.
        assertTrue(classOccursOnDate(e, "2026-09-07"))
        assertFalse(classOccursOnDate(e, "2026-09-14"))
    }

    @Test fun theScheduleTodayAndRemindersAgree() {
        // Reminders and widgets both expand sessions through expandSessions.
        val events = listOf(intensive, term, manual("even", id = "a"), manual("odd", id = "b"), manual("weekly", id = "c"))
        for (day in mondays("2026-08-31", 16)) {
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
