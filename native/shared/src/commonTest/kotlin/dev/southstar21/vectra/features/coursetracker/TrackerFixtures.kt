package dev.southstar21.vectra.features.coursetracker

/** Upsert a full tracker under its canonical key (healing legacy keys), as the tests build their starting data. */
fun applyUpsertTracker(data: CourseTrackerData, tracker: CourseTracker, now: String = nowIso()): CourseTrackerData {
    val canonicalKey = resolveCourseKey(CourseIdentity(courseCode = tracker.courseCode, courseName = tracker.courseName, semester = tracker.semester))
    val cleaned = tracker.copy(
        courseKey = canonicalKey,
        attendance = tracker.attendance.copy(sessions = deduplicateSessions(tracker.attendance.sessions)),
        updatedAt = now,
    )
    return data.copy(trackers = commitTracker(data.trackers, canonicalKey, cleaned, tracker.courseKey.takeIf { it != canonicalKey }))
}
