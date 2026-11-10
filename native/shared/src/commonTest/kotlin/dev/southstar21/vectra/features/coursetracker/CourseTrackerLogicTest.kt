package dev.southstar21.vectra.features.coursetracker

import dev.southstar21.vectra.features.schedule.ClassKind
import dev.southstar21.vectra.features.schedule.ScheduleEvent
import dev.southstar21.vectra.features.schedule.TrackerCourses
import dev.southstar21.vectra.features.schedule.WeekRange
import dev.southstar21.vectra.features.schedule.getBaseCourseCode
import dev.southstar21.vectra.features.schedule.getWeekBeforeStart
import dev.southstar21.vectra.features.schedule.isExamSummary
import dev.southstar21.vectra.features.schedule.normalizeCourseKey
import dev.southstar21.vectra.features.schedule.normalizeTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun event(
    courseName: String,
    courseCode: String = "",
    dayOfWeek: Int = 1,
    startTime: String = "",
    endTime: String = "",
    semester: String = "",
    semesterStart: String? = null,
    semesterEnd: String? = null,
    eventType: String = "",
    source: String? = null,
    attendanceType: String? = null,
    id: String = "",
) = ScheduleEvent(
    id = id,
    courseName = courseName,
    courseCode = courseCode,
    dayOfWeek = dayOfWeek,
    startTime = startTime,
    endTime = endTime,
    semester = semester,
    semesterStart = semesterStart,
    semesterEnd = semesterEnd,
    eventType = eventType,
    source = source,
    attendanceType = attendanceType,
)

internal fun session(
    id: String,
    date: String,
    status: AttendanceStatus,
    title: String = "W",
    type: SessionType = SessionType.Lecture,
    startTime: String? = null,
    notes: String? = null,
    isCustom: Boolean? = null,
) = AttendanceSession(id = id, date = date, startTime = startTime, title = title, sessionType = type, status = status, notes = notes, isCustom = isCustom)

private fun assertClose(expected: Double, actual: Double, digits: Int = 1) =
    assertTrue(abs(expected - actual) < 0.5 * 10.0 / Math10(digits + 1), "expected ≈$expected, was $actual")

private fun Math10(n: Int): Double { var r = 1.0; repeat(n) { r *= 10 }; return r }

class AttendanceCalcTest {
    @Test fun neverGeneratesSessionsForExamOnlyEvents() {
        val exam = event("Accounting", "ACC101", 4, "10:00", "12:00", "2026/27/1", "2026-10-01", "2026-10-01", "exam")
        assertEquals(emptyList(), generateSessionsFromScheduleEvents("Accounting", "ACC101", "2026/27/1", 4, "10:00", "12:00", scheduleEvents = listOf(exam)))
    }

    @Test fun explicitAttendanceTypeWins() {
        val manual = event("Unknown class", "CS101-E01", 1, "10:00", "12:00", semesterStart = "2026-09-07", semesterEnd = "2026-09-07", eventType = "class", attendanceType = "unknown")
        val sessions = generateSessionsFromScheduleEvents("Unknown class", "CS101-E01", scheduleEvents = listOf(manual))
        assertEquals(SessionType.Other, sessions[0].sessionType)
    }

    @Test fun generatesWeeklySessions() {
        val s = generateScheduleSessions(1, "08:15", "09:45", "2025-09-01", "2025-10-01", SessionType.Lecture, "weekly")
        assertTrue(s.size >= 4)
        assertEquals("2025-09-01", s[0].date)
        assertEquals("08:15", s[0].startTime)
        assertEquals(AttendanceStatus.Unmarked, s[0].status)
    }

    @Test fun evenOddFrequency() {
        val weekly = generateScheduleSessions(1, "10:00", "12:00", "2025-09-01", "2025-10-01", frequency = "weekly")
        val even = generateScheduleSessions(1, "10:00", "12:00", "2025-09-01", "2025-10-01", frequency = "even")
        assertTrue(even.size < weekly.size)
    }

    @Test fun preservesMarkedStatuses() {
        val existing = listOf(session("s1", "2025-09-01", AttendanceStatus.Present, "Week 1", notes = "Intro chapter"))
        val merged = generateScheduleSessions(1, "08:15", "09:45", "2025-09-01", "2025-10-01", existingSessions = existing)
        val first = merged.first { it.date == "2025-09-01" }
        assertEquals(AttendanceStatus.Present, first.status)
        assertEquals("Intro chapter", first.notes)
    }

    @Test fun statistics() {
        val sessions = listOf(
            session("1", "2025-09-01", AttendanceStatus.Present),
            session("2", "2025-09-08", AttendanceStatus.Present),
            session("3", "2025-09-15", AttendanceStatus.Absent),
            session("4", "2025-09-22", AttendanceStatus.Excused),
            session("5", "2025-09-29", AttendanceStatus.Cancelled),
            session("6", "2025-10-06", AttendanceStatus.Unmarked),
        )
        val stats = calculateAttendanceStats(sessions, 3, 70.0)
        assertEquals(6, stats.totalSessions)
        assertEquals(2, stats.presentCount)
        assertEquals(1, stats.absentCount)
        assertEquals(1, stats.excusedCount)
        assertEquals(1, stats.cancelledCount)
        assertEquals(3, stats.activeCount)
        assertEquals(4, stats.countedSessions)
        assertClose(50.0, stats.attendancePercent)
        assertEquals(2, stats.absencesRemaining)
    }

    @Test fun measuresAgainstEveryCountedSession() {
        val sessions = List(12) { i -> session("s${i + 1}", "2025-09-${(i + 1).toString().padStart(2, '0')}", if (i < 4) AttendanceStatus.Present else AttendanceStatus.Unmarked) }
        val stats = calculateAttendanceStats(sessions, 3, null, today = "2025-12-01")
        assertEquals(12, stats.countedSessions)
        assertClose(33.3, stats.attendancePercent)
    }

    @Test fun futureUnmarkedLeftOut() {
        val sessions = MutableList(24) { i -> session("s${i + 1}", "2025-09-${(i + 1).toString().padStart(2, '0')}", AttendanceStatus.Unmarked) }
        val before = calculateAttendanceStats(sessions, null, null, today = "2025-08-31")
        assertEquals(0, before.countedSessions)
        assertEquals(24, before.unmarkedCount)
        sessions[0] = sessions[0].copy(status = AttendanceStatus.Present)
        sessions[19] = sessions[19].copy(status = AttendanceStatus.Absent)
        val mid = calculateAttendanceStats(sessions, null, null, today = "2025-09-05")
        assertEquals(6, mid.countedSessions)
        assertClose(16.7, mid.attendancePercent)
    }

    @Test fun noPercentageWithNothingToCount() {
        val stats = calculateAttendanceStats(
            listOf(session("1", "2025-09-01", AttendanceStatus.Cancelled), session("2", "2025-09-08", AttendanceStatus.Excused)),
            null, null,
        )
        assertEquals(0, stats.countedSessions)
        assertEquals(0.0, stats.attendancePercent)
        assertFalse(stats.isBelowMinPercent)
    }

    @Test fun riskTiers() {
        fun tier(absent: Int = 0, active: Int = 10) = getAttendanceRiskTier(absent, 3, 100.0, 70.0, active)
        assertEquals(AttendanceRiskTier.Safe, tier())
        assertEquals(AttendanceRiskTier.Safe, tier(1))
        assertEquals(AttendanceRiskTier.Warning, tier(2))
        assertEquals(AttendanceRiskTier.Warning, tier(3))
        assertEquals(AttendanceRiskTier.Critical, tier(4))
        assertEquals(AttendanceRiskTier.Unknown, tier(0, 0))
    }

    @Test fun belowMinimumAndZeroQuota() {
        assertEquals(AttendanceRiskTier.Critical, getAttendanceRiskTier(1, 5, 57.0, 75.0, 5))
        assertEquals(AttendanceRiskTier.Critical, getAttendanceRiskTier(1, 0, 0.0, null, 5))
    }

    private val physics = listOf(event("Physics I", "PHY101", 1, "08:15", "09:45", semesterStart = "2025-09-01", semesterEnd = "2025-12-08", source = "neptun", eventType = "lecture"))

    @Test fun tombstonedSlotNotRegenerated() {
        val all = generateSessionsFromScheduleEvents("Physics I", "PHY101", scheduleEvents = physics)
        val removed = all[0]
        val key = "${removed.date}_${removed.startTime}_${removed.sessionType.wire}"
        val regenerated = generateSessionsFromScheduleEvents(
            "Physics I", "PHY101", scheduleEvents = physics,
            existingSessions = all.filter { it.id != removed.id }, removedSessionKeys = listOf(key),
        )
        assertEquals(all.size - 1, regenerated.size)
        assertFalse(regenerated.any { it.date == removed.date && it.startTime == removed.startTime })
    }

    @Test fun tombstonedAbsenceStaysDeleted() {
        val all = generateSessionsFromScheduleEvents("Physics I", "PHY101", scheduleEvents = physics)
        val marked = all[0].copy(status = AttendanceStatus.Absent)
        val key = "${marked.date}_${marked.startTime}_${marked.sessionType.wire}"
        val regenerated = generateSessionsFromScheduleEvents("Physics I", "PHY101", scheduleEvents = physics, removedSessionKeys = listOf(key))
        assertFalse(regenerated.any { it.date == marked.date && it.status != AttendanceStatus.Unmarked })
        assertEquals(all.size - 1, regenerated.size)
    }

    @Test fun dedupesKeepingMarks() {
        val d = deduplicateSessions(
            listOf(
                session("1", "2025-09-01", AttendanceStatus.Unmarked, "Lecture"),
                session("2", "2025-09-01", AttendanceStatus.Present, "Lecture"),
                session("3", "2025-09-01", AttendanceStatus.Unmarked, "Lecture"),
                session("4", "2025-09-08", AttendanceStatus.Absent, "Lecture 2"),
                session("5", "2025-09-08", AttendanceStatus.Unmarked, "Lecture 2"),
            ),
        )
        assertEquals(2, d.size)
        assertEquals("2025-09-01", d[0].date); assertEquals(AttendanceStatus.Present, d[0].status)
        assertEquals("2025-09-08", d[1].date); assertEquals(AttendanceStatus.Absent, d[1].status)
    }

    @Test fun neptunRecurringImportsAre14Weeks() {
        val events = List(14) { event("Physics I", "PHY101", 1, "08:15", "09:45", semesterStart = "2025-09-01", semesterEnd = "2025-12-08", source = "neptun", eventType = "lecture", id = "nep-$it") }
        val r = generateSessionsFromScheduleEvents("Physics I", "PHY101", scheduleEvents = events)
        assertEquals(14, r.size)
        assertEquals(14, r.map { it.date }.toSet().size)
        assertEquals("Week 1", r[0].title)
        assertEquals("Week 14", r[13].title)
    }

    @Test fun isolatesTheRequestedSemester() {
        val events = listOf(
            event("Economics", "ECO101", 1, "10:00", "11:30", "2021/22/1", "2021-09-01", "2021-12-15", source = "neptun"),
            event("Economics", "ECO101", 1, "10:00", "11:30", "2025/26/1", "2025-09-01", "2025-12-15", source = "neptun"),
        )
        val r = generateSessionsFromScheduleEvents("Economics", "ECO101", "2025/26/1", scheduleEvents = events)
        assertEquals(14, r.size)
        assertEquals("2025-09-01", r[0].date)
        assertEquals("Week 1", r[0].title)
        assertEquals("Week 14", r[13].title)
    }

    @Test fun lectureSeminarAndLabTogether() {
        val events = listOf(
            event("Computer Science", "CS101-E", 1, "08:15", "09:45", "2025/26/1", "2025-09-01", "2025-12-15", "lecture"),
            event("Computer Science", "CS101-G", 3, "10:00", "11:30", "2025/26/1", "2025-09-01", "2025-12-15", "seminar"),
            event("Computer Science", "CS101-L", 5, "12:00", "13:30", "2025/26/1", "2025-09-01", "2025-12-15", "lab"),
        )
        val r = generateSessionsFromScheduleEvents("Computer Science", semester = "2025/26/1", scheduleEvents = events)
        assertEquals(42, r.size)
        for (t in listOf(SessionType.Lecture, SessionType.Seminar, SessionType.Lab)) {
            val of = r.filter { it.sessionType == t }
            assertEquals(14, of.size, t.name)
            assertEquals("Week 1", of[0].title)
        }
    }

    @Test fun neverGrabsOtherClassesOnTheSameDay() {
        val events = listOf(
            event("Linear Algebra", "MATH101", 1, "08:15", "09:45", "2025/26/1", "2025-09-01", "2025-12-15", "lecture"),
            event("General Chemistry", "CHEM101", 1, "12:00", "13:30", "2025/26/1", "2025-09-01", "2025-12-15", "lecture"),
        )
        val r = generateSessionsFromScheduleEvents("Linear Algebra", "MATH101", "2025/26/1", scheduleEvents = events)
        assertEquals(14, r.size)
        assertTrue(r.all { it.startTime == "08:15" })
    }

    @Test fun oneWeekIntensiveBlock() {
        val events = (1..6).flatMap { d ->
            listOf("08:00" to "09:30", "09:50" to "11:20", "11:40" to "13:10").map { (s, e) ->
                event("Organisational interpersonal skills dev", "G10", d, s, e, semesterStart = "2026-09-07", semesterEnd = "2026-09-12", source = "neptun", eventType = "seminar")
            }
        }
        assertEquals(18, generateSessionsFromScheduleEvents("Organisational interpersonal skills dev", "G10", scheduleEvents = events).size)
    }

    @Test fun irregularSeminarDates() {
        val dates = listOf("2026-09-15", "2026-10-13", "2026-11-03", "2026-12-01")
        val events = dates.map { event("Organisational interpersonal skills dev", "G04", 2, "08:00", "09:30", semesterStart = it, semesterEnd = it, source = "neptun", eventType = "seminar") }
        val r = generateSessionsFromScheduleEvents("Organisational interpersonal skills dev", "G04", scheduleEvents = events)
        assertEquals(dates, r.map { it.date }.sorted())
    }

    @Test fun noPhantomSessions() {
        val other = event("Completely Different Course", "OTHER101", 3, "10:00", "11:30", "2025/26/1", "2025-09-01", "2025-12-15", "lecture")
        assertEquals(0, generateSessionsFromScheduleEvents("My Unmatched Course", "NOMATCH999", "2025/26/1", scheduleEvents = listOf(other)).size)
    }

    @Test fun preservesMarkedWhenNoSlots() {
        val existing = listOf(
            session("custom-1", "2025-09-10", AttendanceStatus.Present, "Custom Session", isCustom = true),
            session("unmarked-1", "2025-09-17", AttendanceStatus.Unmarked, "Week 3"),
        )
        val r = generateSessionsFromScheduleEvents("No Match Course", existingSessions = existing)
        assertEquals(1, r.size)
        assertEquals("2025-09-10", r[0].date)
    }

    @Test fun shortGroupCodesKeyByTitle() {
        val k1 = resolveCourseKey(CourseIdentity("E01", "Basics of Financial Markets", "2026/27/1"))
        val k2 = resolveCourseKey(CourseIdentity("E01", "Introduction to Legal Studies and Taxation", "2026/27/1"))
        val k3 = resolveCourseKey(CourseIdentity("G01", "Introduction to Legal Studies and Taxation", "2026/27/1"))
        assertNotEquals(k1, k2)
        assertEquals(k2, k3)
        assertFalse(TrackerCourses.isSameCourse("Basics of Financial Markets", "E01", "Introduction to Legal Studies and Taxation", "E01"))
    }

    @Test fun manualHolidaySkipping() {
        // Oct 23 2026 is a Friday and a public holiday: a manual weekly Friday class skips it.
        val manual = event("Philosophy", "PHIL1", 5, "10:00", "11:30", semesterStart = "2026-09-04", semesterEnd = "2026-12-11")
        val dates = generateSessionsFromScheduleEvents("Philosophy", "PHIL1", scheduleEvents = listOf(manual)).map { it.date }
        assertFalse("2026-10-23" in dates)
        val neptun = manual.copy(source = "neptun")
        assertTrue("2026-10-23" in generateSessionsFromScheduleEvents("Philosophy", "PHIL1", scheduleEvents = listOf(neptun)).map { it.date })
    }

    @Test fun courseHelpers() {
        assertEquals("physics", TrackerCourses.baseTitle("Physics Lecture"))
        assertEquals("cs101", getBaseCourseCode("CS101-G2"))
        assertEquals("08:00", normalizeTime("8:00"))
        assertNull(normalizeTime("25:00"))
        assertTrue(isExamSummary("Elements of Accounting Midterm Exam"))
        assertTrue(isExamSummary("Mikroökonómia - Vizsga"))
        assertTrue(isExamSummary("Statisztika 1.zh"))
        assertFalse(isExamSummary("Examination Theory"))
        assertEquals(ClassKind.Seminar, TrackerCourses.classKind("7SO20NGMA01M-G01"))
        assertEquals(ClassKind.Lecture, TrackerCourses.classKind("E01"))
        assertEquals(SessionType.Seminar, getEventSessionType("CS101-G", "class", null))
    }

    @Test fun academicWeeks() {
        assertEquals(AcademicWeekInfo(1, "Week 1", true), getAcademicWeekInfo("2026-09-09", "2026-09-07"))
        assertEquals(AcademicWeekInfo(3, "Week 3", true), getAcademicWeekInfo("2026-09-21", "2026-09-07"))
        assertEquals(AcademicWeekInfo(0, "Pre-semester", false), getAcademicWeekInfo("2026-09-01", "2026-09-07"))
        assertNull(getAcademicWeekInfo("2026-09-01", null))
        assertEquals(WeekRange("2026-08-31", "2026-09-06"), getWeekBeforeStart("2026-09-09"))
    }

    @Test fun officialStartIgnoresIntensiveWeek() {
        val events = listOf(
            event("Intensive Skills", "SKL1", 1, "08:00", "09:30", "S", "2026-08-31", "2026-09-04"),
            event("Algebra", "ALG1", 2, "10:00", "11:30", "S", "2026-09-08", "2026-12-15"),
        )
        assertEquals("2026-09-08", TrackerCourses.officialSemesterStart(events))
        assertEquals(setOf(resolveCourseKey(events[0])), TrackerCourses.intensiveCourseKeys(events))
    }
}

class PointsCalcTest {
    private fun a(id: String, name: String, earned: Double, max: Double, weight: Double? = null, mustPass: Boolean? = null, minPass: Double? = null, completed: Boolean? = null) =
        AssessmentItem(id, name, earned, max, weight, mustPass, minPass, completed)

    @Test fun aScoreExactlyAtACustomCutoffReachesIt() {
        // 57 / 100 * 100 is 56.99999999999999 in doubles; it must still earn the 57% grade.
        val scale = listOf(GradingScaleTier(5, 87.0), GradingScaleTier(4, 75.0), GradingScaleTier(3, 62.0), GradingScaleTier(2, 57.0))
        val s = calculatePointsSummary(listOf(a("1", "Exam", 57.0, 100.0)), false, scale, 100.0)
        assertEquals(57.0, s.overallPercentage)
        assertEquals(2, s.estimatedGrade)
        assertEquals(2, estimateGrade(57.0 / 100 * 100, scale))
    }

    @Test fun unweightedSums() {
        val s = calculatePointsSummary(listOf(a("1", "Quiz 1", 10.0, 10.0), a("2", "Midterm", 35.0, 40.0), a("3", "Homework", 20.0, 25.0), a("4", "Final", 0.0, 25.0)), false, DefaultGradingScale)
        assertEquals(65.0, s.totalEarned)
        assertEquals(100.0, s.totalMax)
        assertEquals(75.0, s.scoredMax)
        assertClose(86.67, s.currentPercentage)
        assertEquals(65.0, s.overallPercentage)
        assertEquals(3, s.estimatedGrade)
    }

    @Test fun againstCourseTotal() {
        val items = listOf(a("1", "Midterm", 18.0, 20.0))
        val onHundred = calculatePointsSummary(items, false, DefaultGradingScale, 100.0)
        assertEquals(18.0, onHundred.totalEarned)
        assertEquals(18.0, onHundred.overallPercentage)
        assertEquals(1, onHundred.estimatedGrade)
        val onTwenty = calculatePointsSummary(items, false, DefaultGradingScale, 20.0)
        assertEquals(90.0, onTwenty.overallPercentage)
        assertEquals(5, onTwenty.estimatedGrade)
    }

    @Test fun weighted() {
        val s = calculatePointsSummary(listOf(a("1", "Quiz Avg", 8.0, 10.0, 20.0), a("2", "Midterm", 40.0, 50.0, 40.0), a("3", "Final", 36.0, 40.0, 40.0)), true, DefaultGradingScale)
        assertClose(84.0, s.overallPercentage)
        assertEquals(4, s.estimatedGrade)
    }

    @Test fun failedMustPass() {
        val s = calculatePointsSummary(listOf(a("1", "Homework", 50.0, 50.0), a("2", "Final Exam", 15.0, 50.0, mustPass = true, minPass = 25.0)), false, DefaultGradingScale)
        assertTrue(s.hasFailedMustPass)
        assertEquals(1, s.failedMustPassItems.size)
        assertEquals(1, s.estimatedGrade)
    }

    @Test fun pointsToTarget() = assertEquals(37, calculatePointsToTarget(50.0, 100.0, 5, DefaultGradingScale))

    @Test fun rateStaysWithin100() {
        val s = calculatePointsSummary(listOf(a("1", "Quiz", 10.0, 10.0, completed = true), a("2", "Lab", 15.0, 20.0, completed = false)), totalPoints = 100.0)
        assertEquals(25.0, s.totalEarned)
        assertEquals(10.0, s.scoredMax)
        assertEquals(10.0, s.scoredEarned)
        assertEquals(100.0, s.currentPercentage)
    }

    @Test fun ignoresUnweightedInWeightedMode() {
        val s = calculatePointsSummary(listOf(a("1", "Midterm", 40.0, 50.0, 50.0), a("2", "Quiz", 10.0, 10.0)), true, totalPoints = 100.0)
        assertClose(40.0, s.overallPercentage, 5)
        assertEquals(22, calculatePointsToTarget(s.overallPercentage, 100.0, 3))
    }

    @Test fun gradeForPercent() {
        assertEquals(5, gradeForPercent(90.0, DefaultGradingScale))
        assertEquals(2, gradeForPercent(50.0, DefaultGradingScale))
        assertEquals(1, gradeForPercent(10.0, DefaultGradingScale))
    }
}

class NumericInputTest {
    @Test fun hungarianCommas() {
        assertEquals("12.5", normalizeDecimalInput("12,5"))
        assertEquals(12.5, parseFiniteNumber("12,5"))
        assertEquals("1.2345", normalizeDecimalInput("1.234,5"))
    }

    @Test fun inProgressValues() {
        assertEquals("0", normalizeDecimalInput("0"))
        assertEquals("0.", normalizeDecimalInput("0."))
        assertEquals("0.5", normalizeDecimalInput("0.5"))
        assertEquals(".", normalizeDecimalInput("."))
    }

    @Test fun neverNaN() {
        assertNull(parseFiniteNumber(""))
        assertNull(parseFiniteNumber("."))
        assertNull(parseFiniteNumber("abc"))
        assertEquals(7.5, parseFiniteNumber("7.5"))
        assertEquals(12.0, parseFiniteNumber("12."))
    }

    @Test fun clamps() {
        assertEquals(0.0, clampNumber(-2.0, 0.0, 100.0))
        assertEquals(100.0, clampNumber(120.0, 0.0, 100.0))
        assertEquals(75.0, clampNumber(75.0, 0.0, 100.0))
    }

    @Test fun scaleOrder() {
        assertNull(validateScaleOrder(DefaultGradingScale))
        assertEquals(
            "Grade 5 cutoff must be at least the Grade 4 cutoff (90%).",
            validateScaleOrder(listOf(GradingScaleTier(5, 60.0), GradingScaleTier(4, 90.0), GradingScaleTier(3, 62.0), GradingScaleTier(2, 50.0), GradingScaleTier(1, 0.0))),
        )
        assertEquals(
            "Grade 4 cutoff must be at least the Grade 3 cutoff (80%).",
            validateScaleOrder(listOf(GradingScaleTier(5, 87.0), GradingScaleTier(4, 75.0), GradingScaleTier(3, 80.0), GradingScaleTier(2, 50.0), GradingScaleTier(1, 0.0))),
        )
    }
}

class SessionDateTest {
    @Test fun today() = assertEquals("Today", formatSessionDate("2026-09-16", "2026-09-16"))

    @Test fun dayMonth() {
        assertEquals("Fri 2 Oct", formatSessionDate("2026-10-02", "2026-09-16"))
        assertEquals("Tue 1 Dec", formatSessionDate("2026-12-01", "2026-09-16"))
    }

    @Test fun lookalike() = assertNotEquals("Today", formatSessionDate("2026-09-16T00:00:00", "2026-09-16"))

    @Test fun full() {
        assertEquals("Wed 16 Sept", formatSessionDateFull("2026-09-16"))
        assertEquals(formatSessionDate("2026-10-02", "2026-09-16"), formatSessionDateFull("2026-10-02"))
        assertEquals("", formatSessionDateFull(""))
        assertEquals("not-a-date", formatSessionDateFull("not-a-date"))
    }

    @Test fun tiles() {
        assertEquals(TileParts("SEP", "16", "Wed"), tileParts("2026-09-16"))
        assertNull(tileParts(null))
    }
}

class TrackerTypesTest {
    @Test fun emptyData() {
        val e = createEmptyTrackerData()
        assertEquals(1, e.version)
        assertTrue(e.trackers.isEmpty())
    }

    @Test fun courseKeys() {
        assertEquals("cs101_2025-2026-1", normalizeCourseKey("CS101", "2025-2026-1"))
        assertEquals("algorithms-data-structures_2025-2026-1", normalizeCourseKey(" Algorithms & Data Structures ", " 2025-2026-1 "))
    }

    @Test fun roundTrip() {
        val t = createDefaultTracker("Algorithms", "CS201", "2025-2026-1")
        val parsed = assertNotNull(parseTrackerData(serializeTrackerData(CourseTrackerData(1, mapOf(t.courseKey to t)))))
        assertEquals("Algorithms", parsed.trackers[t.courseKey]?.courseName)
        assertEquals(t, parsed.trackers[t.courseKey])
    }

    @Test fun corrupted() {
        assertNull(parseTrackerData("invalid json"))
        assertNull(parseTrackerData("""{"version": 2}"""))
        assertNull(parseTrackerData("""{"version": 2, "trackers": {}}"""))
    }

    private val legacyBase = """"id":"t1","courseKey":"cs201","courseName":"Algorithms","courseCode":"CS201","semester":"",
        "attendance":{"maxAbsencesAllowed":3,"minAttendancePercent":70,"sessions":[],"removedSessionKeys":[]},"updatedAt":"2026-01-01T00:00:00.000Z""""

    @Test fun legacyUnconfirmed() {
        val raw = """{"version":1,"trackers":{"cs201":{$legacyBase,"points":{"useWeightedCategories":false,"gradingScale":[{"grade":5,"minPercentage":87}],"assessments":[]}}}}"""
        val parsed = assertNotNull(parseTrackerData(raw))
        assertFalse(parsed.trackers.getValue("cs201").settingsConfirmed)
        assertFalse(createDefaultTracker("Algorithms").settingsConfirmed)
        assertEquals(100.0, parsed.trackers.getValue("cs201").points.totalPoints)
        assertEquals(100.0, createDefaultTracker("Algorithms").points.totalPoints)
    }

    @Test fun dropsAssessmentCategories() {
        val raw = """{"version":1,"trackers":{"cs201":{$legacyBase,"points":{"useWeightedCategories":false,"gradingScale":[],
            "assessments":[{"id":"a1","name":"Midterm","category":"midterm","earnedPoints":18,"maxPoints":20},{"id":"a2","name":"Homework","earnedPoints":10,"maxPoints":10}]}}}}"""
        val assessments = assertNotNull(parseTrackerData(raw)).trackers.getValue("cs201").points.assessments
        assertEquals(2, assessments.size)
        assertEquals("Homework", assessments[1].name)
    }

    @Test fun webCompatibleJson() {
        val t = createDefaultTracker("Algorithms", "CS201").copy(
            attendance = TrackerAttendance(null, null, listOf(session("s", "2026-01-01", AttendanceStatus.Present)), emptyList()),
        )
        val json = serializeTrackerData(CourseTrackerData(1, mapOf("k" to t)))
        // Nullable-required limits are explicit nulls; optional keys stay absent (readers reject null for them).
        assertTrue("\"maxAbsencesAllowed\":null" in json)
        assertFalse("\"startTime\"" in json)
        assertFalse("\"instructor\"" in json)
        assertFalse("\"isCustom\"" in json)
    }

    @Test fun parseCollapsesDuplicateSlots() {
        val t = createDefaultTracker("Algorithms").copy(
            attendance = TrackerAttendance(3, null, listOf(
                session("b", "2026-01-02", AttendanceStatus.Unmarked),
                session("a1", "2026-01-01", AttendanceStatus.Unmarked),
                session("a2", "2026-01-01", AttendanceStatus.Absent),
            ), emptyList()),
        )
        val parsed = assertNotNull(parseTrackerData(serializeTrackerData(CourseTrackerData(1, mapOf("k" to t)))))
        assertEquals(listOf("a2", "b"), parsed.trackers.getValue("k").attendance.sessions.map { it.id })
    }
}

private val Intensive = CourseIdentity("X5VX18SUBM01M-G10", "Business Communication Intensive", "2026/27/1")

private fun trackerWithStatus(name: String, code: String, updatedAt: String, status: AttendanceStatus): CourseTracker {
    val t = createDefaultTracker(name, code)
    return t.copy(attendance = t.attendance.copy(sessions = listOf(session("s1", "2026-09-07", status, "Class Session"))), updatedAt = updatedAt)
}

class CourseTrackerStoreTest {
    @Test fun fresherLocalWins() {
        val l = trackerWithStatus("Calculus I", "MATH101", "2026-09-07T10:00:00.000Z", AttendanceStatus.Present)
        val c = trackerWithStatus("Calculus I", "MATH101", "2026-09-07T09:00:00.000Z", AttendanceStatus.Unmarked)
        val m = mergeTrackerData(CourseTrackerData(1, mapOf(l.courseKey to l)), CourseTrackerData(1, mapOf(c.courseKey to c)))
        assertEquals(AttendanceStatus.Present, m.trackers.getValue(l.courseKey).attendance.sessions[0].status)
    }

    @Test fun fresherCloudWins() {
        val l = trackerWithStatus("Calculus I", "MATH101", "2026-09-07T08:00:00.000Z", AttendanceStatus.Present)
        val c = trackerWithStatus("Calculus I", "MATH101", "2026-09-07T11:00:00.000Z", AttendanceStatus.Absent)
        val m = mergeTrackerData(CourseTrackerData(1, mapOf(l.courseKey to l)), CourseTrackerData(1, mapOf(c.courseKey to c)))
        assertEquals(AttendanceStatus.Absent, m.trackers.getValue(l.courseKey).attendance.sessions[0].status)
    }

    @Test fun oneSidedTrackersKept() {
        val l = createDefaultTracker("Physics", "PHY201")
        val c = createDefaultTracker("Chemistry", "CHEM101")
        val m = mergeTrackerData(CourseTrackerData(1, mapOf(l.courseKey to l)), CourseTrackerData(1, mapOf(c.courseKey to c)))
        assertNotNull(m.trackers[l.courseKey]); assertNotNull(m.trackers[c.courseKey])
    }

    @Test fun tieKeepsLocal() {
        val l = trackerWithStatus("Linear Algebra", "MATH202", "2026-09-07T10:00:00.000Z", AttendanceStatus.Excused)
        val c = trackerWithStatus("Linear Algebra", "MATH202", "2026-09-07T10:00:00.000Z", AttendanceStatus.Unmarked)
        val m = mergeTrackerData(CourseTrackerData(1, mapOf(l.courseKey to l)), CourseTrackerData(1, mapOf(c.courseKey to c)))
        assertEquals(AttendanceStatus.Excused, m.trackers.getValue(l.courseKey).attendance.sessions[0].status)
    }

    @Test fun remarkingClearsTombstone() {
        var data = applySetAttendanceStatus(createEmptyTrackerData(), Intensive, "2026-09-07", AttendanceStatus.Present, startTime = "08:00", sessionType = SessionType.Seminar)
        val key = resolveCourseKey(Intensive)
        val slot = sessionSlotKey(data.trackers.getValue(key).attendance.sessions[0])
        val t = data.trackers.getValue(key)
        val deleted = applyUpsertTracker(data, t.copy(attendance = t.attendance.copy(sessions = emptyList(), removedSessionKeys = listOf(slot))))
        assertTrue(slot in deleted.trackers.getValue(key).attendance.removedSessionKeys)
        data = applySetAttendanceStatus(deleted, Intensive, "2026-09-07", AttendanceStatus.Present, startTime = "08:00", sessionType = SessionType.Seminar)
        assertFalse(slot in data.trackers.getValue(key).attendance.removedSessionKeys)
        assertTrue(data.trackers.getValue(key).attendance.sessions.any { it.date == "2026-09-07" && it.status == AttendanceStatus.Present })
    }

    @Test fun canonicalKey() {
        assertEquals("x5vx18subm01m_2026-27-1", resolveCourseKey(Intensive))
        assertEquals("x5vx18subm01m_2026-27-1", resolveCourseKey(Intensive.copy(semester = "2026-27-1")))
    }

    @Test fun checkInCreatesCanonicalTracker() {
        val data = applySetAttendanceStatus(createEmptyTrackerData(), Intensive, "2026-09-07", AttendanceStatus.Present, startTime = "08:00", endTime = "09:30", sessionType = SessionType.Seminar)
        val key = resolveCourseKey(Intensive)
        val created = data.trackers.getValue(key)
        assertEquals(key, created.courseKey)
        assertEquals("Business Communication Intensive", created.courseName)
        assertEquals(1, created.attendance.sessions.size)
        assertEquals(AttendanceStatus.Present, created.attendance.sessions[0].status)
        assertEquals(1, data.trackers.size)
    }

    @Test fun adoptsLegacyKey() {
        val legacy = createDefaultTracker("Business Communication Intensive", "X5VX18SUBM01M-G10").let {
            it.copy(courseKey = "x5vx18subm01m2026271", attendance = it.attendance.copy(sessions = listOf(session("s-legacy", "2026-09-07", AttendanceStatus.Present, "Week 1", SessionType.Seminar, "08:00"))))
        }
        val data = applySetAttendanceStatus(CourseTrackerData(1, mapOf(legacy.courseKey to legacy)), Intensive, "2026-09-07", AttendanceStatus.Absent, startTime = "08:00", sessionType = SessionType.Seminar)
        val key = resolveCourseKey(Intensive)
        assertNotNull(data.trackers[key])
        assertNull(data.trackers[legacy.courseKey])
        assertEquals(AttendanceStatus.Absent, data.trackers.getValue(key).attendance.sessions.first { it.id == "s-legacy" }.status)
        assertEquals(1, data.trackers.getValue(key).attendance.sessions.size)
    }

    @Test fun upsertHealsLegacyKey() {
        val legacy = createDefaultTracker("Business Communication Intensive", "X5VX18SUBM01M-G10", "2026/27/1").copy(courseKey = "x5vx18subm01m")
        val next = applyUpsertTracker(CourseTrackerData(1, mapOf(legacy.courseKey to legacy)), legacy)
        val key = resolveCourseKey(Intensive)
        assertEquals(key, next.trackers.getValue(key).courseKey)
        assertNull(next.trackers[legacy.courseKey])
    }

    @Test fun removeAndRestoreSessions() {
        val identity = CourseIdentity("ALG101", "Algebra")
        val key = resolveCourseKey(identity)
        val t = createDefaultTracker("Algebra", "ALG101").let {
            it.copy(courseKey = key, attendance = it.attendance.copy(sessions = listOf(session("x", "2026-09-07", AttendanceStatus.Absent, startTime = "08:00"))))
        }
        val data = CourseTrackerData(1, mapOf(key to t))
        val removed = applyRemoveSession(data, identity, t.attendance.sessions[0])
        assertTrue(removed.trackers.getValue(key).attendance.sessions.isEmpty())
        assertEquals(listOf("2026-09-07_08:00_lecture"), removed.trackers.getValue(key).attendance.removedSessionKeys)
        val restored = applyRestoreSession(removed, identity, t.attendance.sessions[0])
        assertEquals(1, restored.trackers.getValue(key).attendance.sessions.size)
        assertTrue(restored.trackers.getValue(key).attendance.removedSessionKeys.isEmpty())
    }

    /** 04 #19: a custom session added where one was removed survives the next reconcile. */
    @Test fun aSessionAddedAtARemovedSlotSurvivesTheReconcile() {
        val identity = CourseIdentity("ALG101", "Algebra")
        val key = resolveCourseKey(identity)
        val original = session("x", "2026-09-07", AttendanceStatus.Absent, startTime = "08:00")
        val t = createDefaultTracker("Algebra", "ALG101").let { it.copy(courseKey = key, attendance = it.attendance.copy(sessions = listOf(original))) }
        val removed = applyRemoveSession(CourseTrackerData(1, mapOf(key to t)), identity, original)
        val custom = session("c", "2026-09-07", AttendanceStatus.Present, startTime = "08:00").copy(isCustom = true)
        val attendance = applyAddSession(removed, identity, custom).trackers.getValue(key).attendance
        assertTrue(attendance.removedSessionKeys.isEmpty())
        val reconciled = generateScheduleSessions(
            dayOfWeek = 3,
            startTime = "10:00",
            semesterStart = "2026-09-01",
            semesterEnd = "2026-09-30",
            existingSessions = attendance.sessions,
            removedSessionKeys = attendance.removedSessionKeys,
        )
        assertTrue(reconciled.any { it.id == "c" }, reconciled.map { it.date + " " + it.startTime }.toString())
    }

    // ---- 04 #10: Course Hub writes on a legacy-keyed tracker -------------------------

    private fun legacyTracker(): CourseTracker = createDefaultTracker("Business Communication Intensive", "X5VX18SUBM01M-G10").let {
        it.copy(
            courseKey = "x5vx18subm01m2026271",
            attendance = it.attendance.copy(sessions = listOf(session("s-legacy", "2026-09-07", AttendanceStatus.Present, "Week 1", SessionType.Seminar, "08:00"))),
            points = it.points.copy(assessments = listOf(AssessmentItem("q", "Quiz", 8.0, 10.0))),
        )
    }

    private fun assertMovedToCanonicalKey(legacy: CourseTracker, data: CourseTrackerData): CourseTracker {
        val key = resolveCourseKey(Intensive)
        assertEquals(setOf(key), data.trackers.keys, "the legacy tracker moves; no blank tracker shadows it")
        val moved = data.trackers.getValue(key)
        assertEquals(key, moved.courseKey)
        assertEquals(legacy.courseName, moved.courseName)
        assertEquals(listOf("Quiz"), moved.points.assessments.map { it.name })
        return moved
    }

    @Test fun hubSettingsLandOnALegacyKeyedTracker() {
        val legacy = legacyTracker()
        val data = CourseTrackerData(1, mapOf(legacy.courseKey to legacy))
        val limited = assertMovedToCanonicalKey(legacy, applyLimits(data, Intensive, TrackerLimits(maxAbsencesAllowed = 2, minAttendancePercent = null)))
        assertEquals(2, limited.attendance.maxAbsencesAllowed)
        assertEquals(listOf("s-legacy"), limited.attendance.sessions.map { it.id })
        val scale = listOf(GradingScaleTier(5, 90.0))
        assertEquals(scale, assertMovedToCanonicalKey(legacy, applyGradingScale(data, Intensive, scale)).points.gradingScale)
        assertTrue(assertMovedToCanonicalKey(legacy, applyConfirmSettings(data, Intensive)).settingsConfirmed)
        val added = session("s-extra", "2026-09-08", AttendanceStatus.Present, "Extra", SessionType.Seminar, "10:00")
        assertEquals(listOf("s-legacy", "s-extra"), assertMovedToCanonicalKey(legacy, applyAddSession(data, Intensive, added)).attendance.sessions.map { it.id })
    }

    @Test fun removingASessionWorksOnALegacyKeyedTracker() {
        val legacy = legacyTracker()
        val data = CourseTrackerData(1, mapOf(legacy.courseKey to legacy))
        val removed = assertMovedToCanonicalKey(legacy, applyRemoveSession(data, Intensive, legacy.attendance.sessions[0]))
        assertTrue(removed.attendance.sessions.isEmpty())
        assertEquals(listOf(sessionSlotKey(legacy.attendance.sessions[0])), removed.attendance.removedSessionKeys)
    }

    @Test fun removingASessionOfACourseWithNoTrackerCreatesNone() {
        val data = createEmptyTrackerData()
        assertEquals(data, applyRemoveSession(data, Intensive, session("s", "2026-09-07", AttendanceStatus.Present)))
    }

    @Test fun sessionHolidayRespectsStartTime() {
        val t = createDefaultTracker("Algebra", "ALG101").let {
            it.copy(attendance = it.attendance.copy(sessions = listOf(
                session("a", "2026-09-07", AttendanceStatus.Cancelled, startTime = "08:00"),
                session("b", "2026-09-07", AttendanceStatus.Unmarked, startTime = "14:00", type = SessionType.Seminar),
            )))
        }
        val data = CourseTrackerData(1, mapOf(t.courseKey to t))
        assertTrue(isSessionHoliday(data, "Algebra", "ALG101", "", "2026-09-07", "08:00"))
        assertFalse(isSessionHoliday(data, "Algebra", "ALG101", "", "2026-09-07", "14:00"))
        assertTrue(isSessionHoliday(data, "Algebra", "ALG101", "", "2026-09-07", null))
        assertFalse(isSessionHoliday(data, "Algebra", "ALG101", "", "2026-09-08", null))
    }
}
