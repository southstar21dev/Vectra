package dev.southstar21.vectra.features.coursetracker

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackerMergeTest {
    private val identity = CourseIdentity(courseCode = "ABC1", courseName = "Algebra", semester = "2026/27/1")
    private val lecture = session("l1", "2026-11-02", AttendanceStatus.Unmarked, type = SessionType.Lecture, startTime = "10:00")
    private val seminar = session("s1", "2026-11-03", AttendanceStatus.Unmarked, type = SessionType.Seminar, startTime = "12:00")

    private fun base(): CourseTrackerData =
        applySessions(createEmptyTrackerData(), identity, listOf(lecture, seminar), "2026-11-01T08:00:00Z")

    private fun status(data: CourseTrackerData, id: String): AttendanceStatus =
        data.trackers.values.single().attendance.sessions.first { it.id == id }.status

    @Test fun marksOnTwoDevicesBothSurvive() {
        val phone = applySetAttendanceStatus(base(), identity, "l1", AttendanceStatus.Present, now = "2026-11-02T10:00:00Z")
        val tablet = applySetAttendanceStatus(base(), identity, "s1", AttendanceStatus.Present, now = "2026-11-03T14:00:00Z")
        val merged = mergeTrackerData(phone, tablet)
        assertEquals(AttendanceStatus.Present, status(merged, "l1"))
        assertEquals(AttendanceStatus.Present, status(merged, "s1"))
        val other = mergeTrackerData(tablet, phone)
        assertEquals(AttendanceStatus.Present, status(other, "l1"))
        assertEquals(AttendanceStatus.Present, status(other, "s1"))
    }

    @Test fun aLaterUnmarkBeatsAnEarlierMark() {
        val marked = applySetAttendanceStatus(base(), identity, "l1", AttendanceStatus.Absent, now = "2026-11-02T10:00:00Z")
        val unmarked = applySetAttendanceStatus(marked, identity, "l1", AttendanceStatus.Unmarked, now = "2026-11-02T11:00:00Z")
        assertEquals(AttendanceStatus.Unmarked, status(mergeTrackerData(unmarked, marked), "l1"))
    }

    @Test fun regeneratingSessionsDoesNotBumpTheClock() {
        val start = base()
        val before = start.trackers.values.single().updatedAt
        val after = applySessions(start, identity, listOf(lecture), "2026-12-01T00:00:00Z").trackers.values.single().updatedAt
        assertEquals(before, after)
    }
}
