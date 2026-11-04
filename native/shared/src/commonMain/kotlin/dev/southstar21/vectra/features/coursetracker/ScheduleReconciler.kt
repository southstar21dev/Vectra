package dev.southstar21.vectra.features.coursetracker

import dev.southstar21.vectra.features.schedule.ScheduleEvent
import dev.southstar21.vectra.features.schedule.isExam
import dev.southstar21.vectra.features.schedule.isExamSummary

/**
 * The pure core of schedule reconciliation: populate every course found in a
 * synced schedule with its generated sessions, keeping marked attendance,
 * custom sessions and assessments. [CourseTrackerRepository.syncFromScheduleEvents]
 * wraps it with the cache/cloud I/O.
 */

/**
 * True once the student has put real work into a tracker. Exam trackers an old
 * sync seeded have none; a tracker for "Exam Skills" the student worked on
 * does, so the exam cleanup must never delete it.
 */
fun trackerHasUserWork(tracker: CourseTracker?): Boolean {
    if (tracker == null) return false
    if (tracker.settingsConfirmed) return true
    if (tracker.attendance.removedSessionKeys.isNotEmpty()) return true
    if (tracker.attendance.sessions.any { it.isCustom == true || it.status != AttendanceStatus.Unmarked }) return true
    if (tracker.points.assessments.isNotEmpty()) return true
    if (tracker.points.useWeightedCategories) return true
    return false
}

data class ReconcileResult(
    val data: CourseTrackerData,
    /** Trackers deliberately removed (stale exam trackers, rekeyed legacy keys); the upload must not resurrect them. */
    val dropKeys: List<String>,
    /** Whether anything changed (a sync that changed nothing does not write). */
    val changed: Boolean,
)

fun reconcileTrackersWithSchedule(existing: CourseTrackerData, events: List<ScheduleEvent>, now: String = nowIso()): ReconcileResult {
    // Exams are not attendance-tracked (including exams imported as 'class').
    val classEvents = events.filter { !it.isExam() }

    // Trackers an earlier sync created for exams are stale data, not user work.
    var next: Map<String, CourseTracker> = existing.trackers.filterNot { (_, t) -> isExamSummary(t.courseName) && !trackerHasUserWork(t) }
    val pruned = next.size != existing.trackers.size
    if (classEvents.isEmpty() && !pruned) return ReconcileResult(existing, emptyList(), false)

    data class Info(val name: String, val code: String, val semester: String, val instructor: String, val location: String, val color: String)
    val courses = LinkedHashMap<String, Info>()
    for (evt in classEvents) {
        if (evt.courseName.isEmpty()) continue
        val key = resolveCourseKey(evt)
        if (key !in courses) courses[key] = Info(evt.courseName, evt.courseCode, evt.semester, evt.instructor, evt.location, evt.color)
    }

    for ((key, info) in courses) {
        val identity = CourseIdentity(courseCode = info.code, courseName = info.name, semester = info.semester)
        val found = findTrackerInStore(next, key, identity)
        val tracker = found.tracker
        val existingSessions = tracker?.attendance?.sessions.orEmpty()
        val generated = generateSessionsFromScheduleEvents(
            courseName = info.name,
            courseCode = info.code,
            semester = info.semester,
            scheduleEvents = classEvents,
            existingSessions = existingSessions,
            removedSessionKeys = tracker?.attendance?.removedSessionKeys.orEmpty(),
        )
        if (tracker != null) {
            val merged = tracker.copy(
                courseKey = key,
                instructor = tracker.instructor.nonEmpty() ?: info.instructor.nonEmpty(),
                location = tracker.location.nonEmpty() ?: info.location.nonEmpty(),
                color = tracker.color.nonEmpty() ?: info.color.nonEmpty(),
                attendance = tracker.attendance.copy(sessions = generated),
            )
            // Only a tracker whose content changed gets a fresh updatedAt: bumping
            // every tracker would make this device win merges it should lose.
            val changed = found.legacyKey != null ||
                tracker.courseKey != key ||
                merged.instructor != tracker.instructor ||
                merged.location != tracker.location ||
                merged.color != tracker.color ||
                generated != existingSessions
            if (changed) next = commitTracker(next, key, merged.copy(updatedAt = now), found.legacyKey)
        } else {
            val created = createDefaultTracker(info.name, info.code, info.semester, info.instructor.nonEmpty(), info.location.nonEmpty(), info.color.nonEmpty())
            next = LinkedHashMap(next).apply { put(key, created.copy(courseKey = key, attendance = created.attendance.copy(sessions = generated))) }
        }
    }

    val dropKeys = existing.trackers.keys.filter { it !in next }
    val changedAny = dropKeys.isNotEmpty() || next.any { (k, t) -> existing.trackers[k] !== t }
    return ReconcileResult(CourseTrackerData(1, next), dropKeys, changedAny)
}
