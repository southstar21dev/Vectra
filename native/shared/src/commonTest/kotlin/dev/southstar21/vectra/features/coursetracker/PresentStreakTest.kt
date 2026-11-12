package dev.southstar21.vectra.features.coursetracker

import kotlin.test.Test
import kotlin.test.assertEquals

class PresentStreakTest {
    @Test fun aFirstCheckInIsAStreakOfOne() {
        val sessions = listOf(
            session("w1", "2026-11-05", AttendanceStatus.Unmarked, startTime = "15:30"),
            session("w2", "2026-11-12", AttendanceStatus.Present, startTime = "15:30"),
        )
        assertEquals(1, presentStreak(sessions, "2026-11-12", "15:30"))
    }

    @Test fun earlierWeeksMarkedPresentCount() {
        val sessions = listOf(
            session("w0", "2026-10-22", AttendanceStatus.Unmarked, startTime = "15:30"),
            session("w1", "2026-10-29", AttendanceStatus.Present, startTime = "15:30"),
            session("w2", "2026-11-05", AttendanceStatus.Present, startTime = "15:30"),
            session("w3", "2026-11-12", AttendanceStatus.Present, startTime = "15:30"),
        )
        assertEquals(3, presentStreak(sessions, "2026-11-12", "15:30"))
    }

    @Test fun anAbsenceEndsTheRun() {
        val sessions = listOf(
            session("w1", "2026-10-29", AttendanceStatus.Present, startTime = "08:00"),
            session("w2", "2026-11-05", AttendanceStatus.Absent, startTime = "08:00"),
            session("w3", "2026-11-12", AttendanceStatus.Present, startTime = "08:00"),
        )
        assertEquals(1, presentStreak(sessions, "2026-11-12", "08:00"))
    }

    @Test fun excusedAndCancelledWeeksAreSkipped() {
        val sessions = listOf(
            session("w1", "2026-10-22", AttendanceStatus.Present, startTime = "08:00"),
            session("w2", "2026-10-29", AttendanceStatus.Excused, startTime = "08:00"),
            session("w3", "2026-11-05", AttendanceStatus.Cancelled, startTime = "08:00"),
            session("w4", "2026-11-12", AttendanceStatus.Present, startTime = "08:00"),
        )
        assertEquals(2, presentStreak(sessions, "2026-11-12", "08:00"))
    }

    @Test fun laterSessionsAreIgnored() {
        val sessions = listOf(
            session("a", "2026-11-12", AttendanceStatus.Present, startTime = "08:00"),
            session("b", "2026-11-19", AttendanceStatus.Unmarked, startTime = "08:00"),
        )
        assertEquals(1, presentStreak(sessions, "2026-11-12", "08:00"))
    }

    @Test fun theTestAccountsSeminarWeeks() {
        // The real case: seminars on the 22nd and 5th marked, the lectures between them not.
        // The bridge passes only the seminar sessions, so the run is 3.
        val seminars = listOf(
            session("s1", "2026-10-15", AttendanceStatus.Unmarked, type = SessionType.Seminar, startTime = "15:30"),
            session("s2", "2026-10-22", AttendanceStatus.Present, type = SessionType.Seminar, startTime = "15:30"),
            session("s3", "2026-11-05", AttendanceStatus.Present, type = SessionType.Seminar, startTime = "15:30"),
            session("s4", "2026-11-12", AttendanceStatus.Present, type = SessionType.Seminar, startTime = "15:30"),
        )
        assertEquals(3, presentStreak(seminars, "2026-11-12", "15:30"))
    }
}
