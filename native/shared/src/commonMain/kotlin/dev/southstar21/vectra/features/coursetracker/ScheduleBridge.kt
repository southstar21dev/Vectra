package dev.southstar21.vectra.features.coursetracker

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.southstar21.vectra.features.schedule.NeptunSyncListener
import dev.southstar21.vectra.features.schedule.ScheduleCourseTrackerHooks
import dev.southstar21.vectra.features.schedule.ScheduleEvent
import dev.southstar21.vectra.features.schedule.ScheduleRepository
import dev.southstar21.vectra.features.schedule.AttendanceStatus as CardStatus
import dev.southstar21.vectra.features.schedule.normalizeTime

/**
 * The course tracker's side of the schedule: the class card's check-in button and
 * the key the Course Hub is opened with.
 */
class CourseTrackerScheduleHooks(tracker: Lazy<CourseTrackerRepository>) : ScheduleCourseTrackerHooks {
    private val repo by tracker

    private fun identity(e: ScheduleEvent) =
        CourseIdentity(courseCode = e.courseCode, courseName = e.courseName, semester = e.semester, instructor = e.instructor, location = e.location)

    /** The session for this card's date + start time. */
    private fun activeSession(data: CourseTrackerData, event: ScheduleEvent, date: String): AttendanceSession? {
        val tracker = findTracker(data, identity(event)) ?: return null
        val sessions = tracker.attendance.sessions.filter { it.date == date }
        if (sessions.isEmpty()) return null
        if (event.startTime.isNotEmpty()) {
            val start = normalizeTime(event.startTime)
            sessions.firstOrNull { it.startTime != null && normalizeTime(it.startTime) == start }?.let { return it }
            val otherTimed = sessions.any { it.startTime != null && normalizeTime(it.startTime) != start }
            return if (!otherTimed) sessions.firstOrNull { it.startTime == null } else null
        }
        return sessions.first()
    }

    @Composable
    override fun attendanceStatus(event: ScheduleEvent, date: String): CardStatus {
        val data by repo.data.collectAsStateWithLifecycle()
        return when (activeSession(data, event, date)?.status) {
            AttendanceStatus.Present -> CardStatus.Present
            AttendanceStatus.Absent -> CardStatus.Absent
            AttendanceStatus.Excused -> CardStatus.Excused
            AttendanceStatus.Cancelled -> CardStatus.Cancelled
            else -> CardStatus.Unmarked
        }
    }

    override fun setAttendanceStatus(event: ScheduleEvent, date: String, status: CardStatus) {
        val session = activeSession(repo.data.value, event, date)
        val mapped = when (status) {
            CardStatus.Present -> AttendanceStatus.Present
            CardStatus.Absent -> AttendanceStatus.Absent
            CardStatus.Excused -> AttendanceStatus.Excused
            CardStatus.Cancelled -> AttendanceStatus.Cancelled
            CardStatus.Unmarked -> AttendanceStatus.Unmarked
        }
        repo.setAttendanceStatus(
            identity(event),
            session?.id ?: date,
            mapped,
            startTime = event.startTime,
            endTime = event.endTime,
            title = event.courseName,
            sessionType = getEventSessionType(event),
        )
    }

    override fun courseKey(event: ScheduleEvent): String = resolveCourseKey(event)
}

/** After a Neptun sync, refresh the tracker blob and reconcile it against the new schedule. */
class CourseTrackerScheduleSync(
    private val tracker: Lazy<CourseTrackerRepository>,
    private val schedule: Lazy<ScheduleRepository>,
) : NeptunSyncListener {
    override suspend fun onNeptunEventsPersisted() {
        val repo = tracker.value
        repo.refresh()
        repo.syncFromScheduleEvents(schedule.value.events.value)
    }
}
