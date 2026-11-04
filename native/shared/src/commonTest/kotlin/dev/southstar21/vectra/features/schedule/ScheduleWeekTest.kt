package dev.southstar21.vectra.features.schedule

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleWeekTest {
    private val today = "2026-10-29"

    private fun ev(
        courseName: String = "Course",
        courseCode: String = "",
        semester: String = "2026/27/1",
        start: String? = null,
        end: String? = null,
        startTime: String = "08:00",
    ) = ScheduleEvent(
        id = "e1", userId = "u1", courseName = courseName, courseCode = courseCode, dayOfWeek = 1,
        startTime = startTime, endTime = "09:30", color = "#4A90D9", semester = semester,
        semesterStart = start, semesterEnd = end, eventType = "class",
    )

    @Test fun weeksForSeptember2026() {
        val weeks = getWeeksInMonth(LocalDate(2026, 9, 1), today)
        assertTrue(weeks.size >= 4)
        assertEquals("2026-08-31", weeks[0].startStr)
        assertEquals("2026-09-06", weeks[0].endStr)
        assertEquals(0, weeks[0].index)
        assertEquals("2026-09-07", weeks[1].startStr)
        assertEquals("2026-09-13", weeks[1].endStr)
    }

    @Test fun labelsWeeks() {
        val weeks = getWeeksInMonth(LocalDate(2026, 10, 1), today)
        assertTrue(weeks.size >= 4)
        assertTrue(weeks[0].rangeLabel.isNotEmpty())
    }

    @Test fun startWeekIsWeekOne() =
        assertEquals(AcademicWeekInfo(1, "Week 1", true), getAcademicWeekInfo("2026-09-07", "2026-09-07"))

    @Test fun numbersRelativeToMonday() {
        val s = "2026-09-09"
        assertEquals(AcademicWeekInfo(1, "Week 1", true), getAcademicWeekInfo("2026-09-07", s))
        assertEquals(AcademicWeekInfo(1, "Week 1", true), getAcademicWeekInfo("2026-09-10", s))
        assertEquals(AcademicWeekInfo(2, "Week 2", true), getAcademicWeekInfo("2026-09-14", s))
        assertEquals(AcademicWeekInfo(3, "Week 3", true), getAcademicWeekInfo("2026-09-21", s))
    }

    @Test fun preSemester() =
        assertEquals(AcademicWeekInfo(0, "Pre-semester", false), getAcademicWeekInfo("2026-08-31", "2026-09-09"))

    @Test fun nullForMissingOrInvalid() {
        assertNull(getAcademicWeekInfo("2026-09-07", null))
        assertNull(getAcademicWeekInfo("2026-09-07", "not-a-date"))
        assertNull(getAcademicWeekInfo("", "2026-09-07"))
        assertNull(getAcademicWeekInfo("2026-9-7", "2026-09-07"))
    }

    @Test fun ignoresIntensiveBlockBeforeStart() {
        val events = listOf(
            ev(courseName = "Business Communication Intensive", start = "2026-08-31", end = "2026-09-04"),
            ev(courseName = "Calculus", start = "2026-09-07", end = "2026-12-15"),
        )
        assertEquals("2026-09-07", ScheduleCourses.officialSemesterStart(events))
    }

    @Test fun earliestRegularStart() {
        val events = listOf(
            ev(courseName = "Physics", start = "2026-09-14", end = "2026-12-15"),
            ev(courseName = "Calculus", start = "2026-09-07", end = "2026-12-15"),
        )
        assertEquals("2026-09-07", ScheduleCourses.officialSemesterStart(events))
    }

    @Test fun fallsBackToIntensiveWhenAllIntensive() =
        assertEquals("2026-08-31", ScheduleCourses.officialSemesterStart(listOf(ev(courseName = "Blokk képzés", start = "2026-08-31", end = "2026-09-04"))))

    private val g10Dates = listOf("2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10", "2026-09-11", "2026-09-12")

    @Test fun concentratedIntensiveWeekG10() {
        val intensive = g10Dates.flatMap { d ->
            listOf("08:00", "09:50", "11:40").map { t ->
                ev(courseName = "Organisational interpersonal skills dev", courseCode = "G10", start = d, end = d, startTime = t)
            }
        }
        val regular = ev(courseName = "Calculus", courseCode = "MATH101-E01", start = "2026-09-14", end = "2026-12-15")
        assertEquals("2026-09-14", ScheduleCourses.officialSemesterStart(intensive + regular))
    }

    private val g04 = listOf("2026-09-15", "2026-10-13", "2026-11-03", "2026-12-01").map { d ->
        ev(courseName = "Organisational interpersonal skills dev", courseCode = "G04", start = d, end = d)
    }

    @Test fun spreadOutSeminarsNotIntensive() = assertEquals("2026-09-15", ScheduleCourses.officialSemesterStart(g04))

    @Test fun nullWithoutStart() {
        assertNull(ScheduleCourses.officialSemesterStart(listOf(ev())))
        assertNull(ScheduleCourses.officialSemesterStart(emptyList()))
    }

    @Test fun intensiveKeysPreSemesterWeek() {
        val g10 = g10Dates.map { d -> ev(courseName = "Organisational interpersonal skills dev", courseCode = "G10", start = d, end = d) }
        val regular = ev(courseName = "Calculus", courseCode = "MATH101-E01", start = "2026-09-14", end = "2026-12-15")
        val keys = ScheduleCourses.intensiveCourseKeys(g10 + regular)
        assertEquals(1, keys.size)
        assertTrue("organisational-interpersonal-skills-dev_2026-27-1" in keys)
    }

    @Test fun intensiveKeysIgnoreSpreadOut() = assertEquals(0, ScheduleCourses.intensiveCourseKeys(g04).size)

    @Test fun intensivePerSemester() {
        val pastI = listOf("2026-01-26", "2026-01-27", "2026-01-28", "2026-01-29", "2026-01-30").map { d ->
            ev(courseName = "Org skills dev", courseCode = "ORG-G10", semester = "2025/26/2", start = d, end = d)
        }
        val pastR = ev(courseName = "Calculus", courseCode = "MATH101", semester = "2025/26/2", start = "2026-02-02", end = "2026-05-29")
        val curI = listOf("2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10", "2026-09-11").map { d ->
            ev(courseName = "Org skills dev", courseCode = "ORG-G10", semester = "2026/27/1", start = d, end = d)
        }
        val curR = ev(courseName = "Calculus", courseCode = "MATH101", semester = "2026/27/1", start = "2026-09-14", end = "2026-12-18")
        val keys = ScheduleCourses.intensiveCourseKeys(pastI + pastR + curI + curR)
        assertTrue("org-skills-dev_2025-26-2" in keys)
        assertTrue("org-skills-dev_2026-27-1" in keys)
    }

    @Test fun defaultWeekPast() {
        val weeks = getWeeksInMonth(LocalDate(2024, 9, 1), today)
        assertEquals("2024-08-26", weeks[0].startStr)
        assertEquals("2024-09-09", weeks[2].startStr)
        assertEquals(2, resolveDefaultWeekIndex(weeks, "past", "2024-09-09", 0))
    }

    @Test fun defaultWeekUpcoming() {
        val weeks = getWeeksInMonth(LocalDate(2026, 9, 1), today)
        assertEquals(1, resolveDefaultWeekIndex(weeks, "upcoming", "2026-09-07", 0))
    }

    @Test fun defaultWeekWithOffset() {
        val weeks = getWeeksInMonth(LocalDate(2024, 9, 1), today)
        assertEquals(0, resolveDefaultWeekIndex(weeks, "past", "2024-09-09", 1))
    }
}
