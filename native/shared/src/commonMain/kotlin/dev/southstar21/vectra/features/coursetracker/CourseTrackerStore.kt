package dev.southstar21.vectra.features.coursetracker

import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.features.schedule.TrackerCourses
import dev.southstar21.vectra.features.schedule.getBaseCourseCode
import dev.southstar21.vectra.features.schedule.normalizeTime
import kotlin.time.Instant

/**
 * The pure tracker-store layer plus the mutations the repository applies
 * through `write`. The map is keyed by
 * [resolveCourseKey]; trackers left under older mangled keys are adopted and
 * rekeyed on their next write.
 */

private fun updatedAtMs(tracker: CourseTracker): Long =
    runCatching { Instant.parse(tracker.updatedAt).toEpochMilliseconds() }.getOrDefault(0L)

/**
 * Local vs cloud: the newest version of each tracker wins (local on a tie);
 * trackers on one side only are kept. A stale cloud snapshot must never revert
 * a just-applied local update whose upload is still in flight.
 */
fun mergeTrackerData(local: CourseTrackerData, cloud: CourseTrackerData): CourseTrackerData {
    val trackers = LinkedHashMap(cloud.trackers)
    for ((key, localTracker) in local.trackers) {
        val cloudTracker = trackers[key]
        if (cloudTracker == null || updatedAtMs(localTracker) >= updatedAtMs(cloudTracker)) trackers[key] = localTracker
    }
    return CourseTrackerData(1, trackers)
}

private fun splitCourseKey(key: String): Pair<String, String> {
    val idx = key.lastIndexOf('_')
    return if (idx > 0 && idx < key.length - 1) key.substring(0, idx) to key.substring(idx + 1) else key to ""
}

private val DateLike = Regex("^\\d{4}-\\d{2}-\\d{2}$")

private fun isDateLike(value: String) = DateLike.matches(value)

/** A tracker under a legacy key for the same course+semester; never from another semester. */
private fun findLegacyTracker(trackers: Map<String, CourseTracker>, canonicalKey: String, identity: CourseIdentity): Pair<String, CourseTracker>? {
    val semester = splitCourseKey(canonicalKey).second
    val baseCode = getBaseCourseCode(identity.courseCode)
    val baseTitle = TrackerCourses.baseTitle(identity.courseName)
    if (baseCode.isEmpty() && baseTitle.isEmpty()) return null
    for ((key, tracker) in trackers) {
        if (key == canonicalKey) continue
        val storedSem = splitCourseKey(key).second
        if (storedSem.isNotEmpty() && storedSem != semester) continue
        val codeMatch = baseCode.length >= 4 && tracker.courseCode.isNotEmpty() && getBaseCourseCode(tracker.courseCode) == baseCode
        val titleMatch = baseTitle.length >= 3 && tracker.courseName.isNotEmpty() && TrackerCourses.baseTitle(tracker.courseName) == baseTitle
        if (codeMatch || titleMatch) return key to tracker
    }
    return null
}

data class TrackerLookup(val key: String, val tracker: CourseTracker?, val legacyKey: String? = null)

/** Canonical key, else adopt a legacy tracker, else a fresh one under the canonical key. */
fun getOrCreateTrackerFromStore(trackers: Map<String, CourseTracker>, canonicalKey: String, identity: CourseIdentity): TrackerLookup {
    trackers[canonicalKey]?.let { return TrackerLookup(canonicalKey, it) }
    findLegacyTracker(trackers, canonicalKey, identity)?.let { return TrackerLookup(canonicalKey, it.second, it.first) }
    val created = createDefaultTracker(
        courseName = identity.courseName.nonEmpty() ?: canonicalKey,
        courseCode = identity.courseCode,
        semester = identity.semester,
        instructor = identity.instructor,
        location = identity.location,
        color = identity.color,
    ).copy(courseKey = canonicalKey)
    return TrackerLookup(canonicalKey, created)
}

/** Read-only lookup: exact canonical key first, then legacy adoption. */
fun findTrackerInStore(trackers: Map<String, CourseTracker>, canonicalKey: String, identity: CourseIdentity? = null): TrackerLookup {
    if (canonicalKey.isEmpty()) return TrackerLookup(canonicalKey, null)
    trackers[canonicalKey]?.let { return TrackerLookup(canonicalKey, it) }
    if (identity != null) findLegacyTracker(trackers, canonicalKey, identity)?.let { return TrackerLookup(canonicalKey, it.second, it.first) }
    return TrackerLookup(canonicalKey, null)
}

/** Places a tracker at its canonical key, dropping any legacy key it replaced. */
fun commitTracker(trackers: Map<String, CourseTracker>, key: String, tracker: CourseTracker, legacyKey: String? = null): Map<String, CourseTracker> {
    val next = LinkedHashMap(trackers)
    if (legacyKey != null && legacyKey != key) next.remove(legacyKey)
    next[key] = tracker
    return next
}

/** Get-or-create by an already-canonical key (the modal's string-key operations). */
fun getTrackerOrCreateByKey(trackers: Map<String, CourseTracker>, courseKey: String): Pair<String, CourseTracker> {
    val key = courseKey.ifEmpty { "unknown" }
    trackers[key]?.let { return key to it }
    return key to createDefaultTracker(courseName = key).copy(courseKey = key)
}

/** Mark the session identified by [identifier] (a session id or a date). */
fun applySetAttendanceStatus(
    data: CourseTrackerData,
    identity: CourseIdentity,
    identifier: String,
    status: AttendanceStatus,
    startTime: String? = null,
    endTime: String? = null,
    title: String? = null,
    sessionType: SessionType? = null,
    now: String = nowIso(),
): CourseTrackerData {
    val canonicalKey = resolveCourseKey(identity)
    val found = getOrCreateTrackerFromStore(data.trackers, canonicalKey, identity)
    val tracker = found.tracker!!
    val sessions = deduplicateSessions(tracker.attendance.sessions)
    // Zero-padded HH:mm so `8:00` and `08:00` never diverge.
    val normStart = normalizeTime(startTime)
    val normEnd = normalizeTime(endTime)
    val isDate = isDateLike(identifier)

    var target = sessions.firstOrNull { it.id == identifier }
    if (target == null && isDate && normStart != null) {
        target = sessions.firstOrNull { it.date == identifier && it.startTime.nonEmpty() != null && normalizeTime(it.startTime) == normStart }
    }
    if (target == null && isDate && normStart == null) {
        target = sessions.filter { it.date == identifier }.singleOrNull()
    }

    val nextSessions: List<AttendanceSession>
    val changedSlot: String
    if (target != null) {
        val updated = target.copy(startTime = target.startTime.nonEmpty() ?: normStart, endTime = target.endTime.nonEmpty() ?: normEnd, status = status)
        changedSlot = sessionSlotKey(updated)
        nextSessions = sessions.map { if (it.id == target.id) updated else it }
    } else {
        val date = if (isDate) identifier else nowInBudapest().ymd
        val created = AttendanceSession(
            id = if (isDate) "${date}_${normStart ?: "00-00"}_${genId()}" else identifier,
            date = date,
            startTime = normStart,
            endTime = normEnd,
            title = title.nonEmpty() ?: tracker.courseName.nonEmpty() ?: "Class Session",
            sessionType = sessionType ?: SessionType.Lecture,
            status = status,
        )
        changedSlot = sessionSlotKey(created)
        nextSessions = deduplicateSessions(sessions + created)
    }
    // Marking a slot again is an explicit "I want this session": drop its tombstone.
    val removed = if (status == AttendanceStatus.Unmarked) tracker.attendance.removedSessionKeys
    else tracker.attendance.removedSessionKeys.filter { it != changedSlot }
    return data.copy(
        trackers = commitTracker(
            data.trackers,
            canonicalKey,
            tracker.copy(attendance = tracker.attendance.copy(sessions = nextSessions, removedSessionKeys = removed), updatedAt = now),
            found.legacyKey,
        ),
    )
}

/** Upsert a full tracker under its canonical key (healing legacy keys). */
fun applyUpsertTracker(data: CourseTrackerData, tracker: CourseTracker, now: String = nowIso()): CourseTrackerData {
    val canonicalKey = resolveCourseKey(CourseIdentity(courseCode = tracker.courseCode, courseName = tracker.courseName, semester = tracker.semester))
    val cleaned = tracker.copy(
        courseKey = canonicalKey,
        attendance = tracker.attendance.copy(sessions = deduplicateSessions(tracker.attendance.sessions)),
        updatedAt = now,
    )
    return data.copy(trackers = commitTracker(data.trackers, canonicalKey, cleaned, tracker.courseKey.takeIf { it != canonicalKey }))
}

/** addAttendanceSession */
fun applyAddSession(data: CourseTrackerData, courseKey: String, session: AttendanceSession, now: String = nowIso()): CourseTrackerData {
    val (key, tracker) = getTrackerOrCreateByKey(data.trackers, courseKey)
    val next = tracker.copy(
        attendance = tracker.attendance.copy(sessions = deduplicateSessions(tracker.attendance.sessions + session)),
        updatedAt = now,
    )
    return data.copy(trackers = LinkedHashMap(data.trackers).apply { put(key, next) })
}

/** removeAttendanceSession: also tombstones the slot so the reconciler never regenerates it. */
fun applyRemoveSession(data: CourseTrackerData, courseKey: String, session: AttendanceSession, now: String = nowIso()): CourseTrackerData {
    val found = data.trackers[courseKey] ?: return data
    val next = found.copy(
        attendance = found.attendance.copy(
            sessions = found.attendance.sessions.filter { it.id != session.id },
            removedSessionKeys = (found.attendance.removedSessionKeys + sessionSlotKey(session)).distinct(),
        ),
        updatedAt = now,
    )
    return data.copy(trackers = LinkedHashMap(data.trackers).apply { put(courseKey, next) })
}

/** restoreAttendanceSession (undo). */
fun applyRestoreSession(data: CourseTrackerData, courseKey: String, session: AttendanceSession, now: String = nowIso()): CourseTrackerData {
    val found = data.trackers[courseKey] ?: return data
    val slot = sessionSlotKey(session)
    val next = found.copy(
        attendance = found.attendance.copy(
            sessions = deduplicateSessions(found.attendance.sessions + session),
            removedSessionKeys = found.attendance.removedSessionKeys.filter { it != slot },
        ),
        updatedAt = now,
    )
    return data.copy(trackers = LinkedHashMap(data.trackers).apply { put(courseKey, next) })
}

/** Assessment writes resolve the tracker the way the sheet reads it (canonical, then legacy). */
fun applyAssessments(
    data: CourseTrackerData,
    identity: CourseIdentity,
    now: String = nowIso(),
    mutate: (List<AssessmentItem>) -> List<AssessmentItem>,
): CourseTrackerData {
    val found = getOrCreateTrackerFromStore(data.trackers, resolveCourseKey(identity), identity)
    val tracker = found.tracker!!
    val next = tracker.copy(courseKey = found.key, points = tracker.points.copy(assessments = mutate(tracker.points.assessments)), updatedAt = now)
    return data.copy(trackers = commitTracker(data.trackers, found.key, next, found.legacyKey))
}

private fun CourseTrackerData.withTracker(courseKey: String, now: String, change: (CourseTracker) -> CourseTracker): CourseTrackerData {
    val (key, tracker) = getTrackerOrCreateByKey(trackers, courseKey)
    return copy(trackers = LinkedHashMap(trackers).apply { put(key, change(tracker).copy(updatedAt = now)) })
}

fun applyGradingScale(data: CourseTrackerData, courseKey: String, scale: List<GradingScaleTier>, now: String = nowIso()) =
    data.withTracker(courseKey, now) { it.copy(settingsConfirmed = true, points = it.points.copy(gradingScale = scale)) }

/** Marks the attendance limits + grading scale as checked against the syllabus. */
fun applyConfirmSettings(data: CourseTrackerData, courseKey: String, now: String = nowIso()) =
    data.withTracker(courseKey, now) { it.copy(settingsConfirmed = true) }

data class TrackerLimits(
    val maxAbsencesAllowed: Int?,
    val minAttendancePercent: Double?,
    val useWeightedCategories: Boolean? = null,
    val totalPoints: Double? = null,
)

fun applyLimits(data: CourseTrackerData, courseKey: String, limits: TrackerLimits, now: String = nowIso()) =
    data.withTracker(courseKey, now) {
        it.copy(
            settingsConfirmed = true,
            attendance = it.attendance.copy(maxAbsencesAllowed = limits.maxAbsencesAllowed, minAttendancePercent = limits.minAttendancePercent),
            points = it.points.copy(
                useWeightedCategories = limits.useWeightedCategories ?: it.points.useWeightedCategories,
                totalPoints = limits.totalPoints ?: it.points.totalPoints,
            ),
        )
    }

/** getTracker: the tracker for a course (canonical or legacy) with deduplicated sessions, or null. */
fun findTracker(data: CourseTrackerData, identity: CourseIdentity): CourseTracker? {
    if (identity.courseCode.isNullOrEmpty() && identity.courseName.isNullOrEmpty()) return null
    val t = findTrackerInStore(data.trackers, resolveCourseKey(identity), identity).tracker ?: return null
    return t.copy(attendance = t.attendance.copy(sessions = deduplicateSessions(t.attendance.sessions)))
}

/** getOrCreateTracker: never null; a fresh tracker until the first write stores it. */
fun findOrCreateTracker(data: CourseTrackerData, identity: CourseIdentity): CourseTracker {
    val key = resolveCourseKey(identity)
    val t = getOrCreateTrackerFromStore(data.trackers, key, identity).tracker!!
    return t.copy(courseKey = key, attendance = t.attendance.copy(sessions = deduplicateSessions(t.attendance.sessions)))
}

/**
 * isSessionHoliday: whether the class at [dateStr] (and [startTime]) was marked
 * "No class". A cancelled 08:00 lecture must not hide the same course's 14:00 seminar.
 */
fun isSessionHoliday(
    data: CourseTrackerData,
    courseName: String,
    courseCode: String?,
    semester: String?,
    dateStr: String?,
    startTime: String?,
): Boolean {
    if (dateStr.isNullOrEmpty()) return false
    val tracker = findTracker(data, CourseIdentity(courseCode = courseCode, courseName = courseName, semester = semester)) ?: return false
    val cancelled = tracker.attendance.sessions.filter { it.date == dateStr && it.status == AttendanceStatus.Cancelled }
    if (cancelled.isEmpty()) return false
    val norm = normalizeTime(startTime)
    if (!startTime.isNullOrEmpty() && norm != null) {
        if (cancelled.any { it.startTime.nonEmpty() != null && normalizeTime(it.startTime) == norm }) return true
        val otherTimed = tracker.attendance.sessions.any { it.date == dateStr && it.startTime.nonEmpty() != null && normalizeTime(it.startTime) != norm }
        if (otherTimed) return cancelled.any { it.startTime.nonEmpty() == null }
    }
    return true
}
