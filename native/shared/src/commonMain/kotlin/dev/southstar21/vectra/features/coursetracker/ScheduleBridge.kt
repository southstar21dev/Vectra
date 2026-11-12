package dev.southstar21.vectra.features.coursetracker

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.southstar21.vectra.features.schedule.NeptunSyncListener
import dev.southstar21.vectra.features.schedule.ScheduleCourseTrackerHooks
import dev.southstar21.vectra.features.schedule.ScheduleEvent
import dev.southstar21.vectra.features.schedule.AttendanceStatus as CardStatus
import dev.southstar21.vectra.features.schedule.normalizeTime
import dev.southstar21.vectra.core.time.nowInBudapest

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
        return cardStatus(data, event, date)
    }

    private fun cardStatus(data: CourseTrackerData, event: ScheduleEvent, date: String): CardStatus =
        when (activeSession(data, event, date)?.status) {
            AttendanceStatus.Present -> CardStatus.Present
            AttendanceStatus.Absent -> CardStatus.Absent
            AttendanceStatus.Excused -> CardStatus.Excused
            AttendanceStatus.Cancelled -> CardStatus.Cancelled
            else -> CardStatus.Unmarked
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

    override fun presentStreak(event: ScheduleEvent, date: String): Int {
        val tracker = findTracker(repo.data.value, identity(event)) ?: return 0
        // "In a row" for this class: a seminar's streak skips the same course's lectures and labs.
        val type = getEventSessionType(event)
        return presentStreak(tracker.attendance.sessions.filter { it.sessionType == type }, date, normalizeTime(event.startTime))
    }
}

/**
 * One class's sessions attended in a row (the caller passes one course's sessions of one type),
 * counted back from the session at [date] / [startTime]
 * (included). Excused and cancelled sessions are skipped; an absence or a past class nobody
 * checked in to ends the run. Later sessions are ignored.
 */
internal fun presentStreak(sessions: List<AttendanceSession>, date: String, startTime: String?): Int {
    val upTo = "$date ${startTime.orEmpty()}"
    fun at(s: AttendanceSession) = "${s.date} ${normalizeTime(s.startTime).orEmpty()}"
    return deduplicateSessions(sessions)
        .filter { at(it) <= upTo }
        .filter { it.status != AttendanceStatus.Excused && it.status != AttendanceStatus.Cancelled }
        .sortedByDescending { at(it) }
        .takeWhile { it.status == AttendanceStatus.Present }
        .size
}

/** After a Neptun sync, refresh the tracker blob and reconcile it against the new schedule. */
class CourseTrackerScheduleSync(private val tracker: Lazy<CourseTrackerRepository>) : NeptunSyncListener {
    override suspend fun onNeptunEventsPersisted(events: List<ScheduleEvent>) {
        val repo = tracker.value
        repo.refresh()
        repo.syncFromScheduleEvents(events)
    }
}
