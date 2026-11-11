package dev.southstar21.vectra.features.coursetracker

import dev.southstar21.vectra.core.time.isYmdString
import dev.southstar21.vectra.core.time.daysSinceSunday
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.core.time.parseIsoDate
import dev.southstar21.vectra.core.time.ymd
import dev.southstar21.vectra.features.holidays.isHungarianPublicHoliday
import dev.southstar21.vectra.features.schedule.ScheduleEvent
import dev.southstar21.vectra.features.schedule.TrackerCourses
import dev.southstar21.vectra.features.schedule.getEventFrequency
import dev.southstar21.vectra.features.schedule.isExam
import dev.southstar21.vectra.features.schedule.normalizeTime
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

private val YmdPattern = Regex("^\\d{4}-\\d{2}-\\d{2}$")

data class AcademicWeekInfo(val weekNumber: Int, val label: String, val isStarted: Boolean)

/**
 * The academic week of [dateStr] the way the schedule's week switcher numbers
 * it: the week containing the semester start is Week 1, earlier is "Pre-semester".
 */
fun getAcademicWeekInfo(dateStr: String, semesterStartStr: String?): AcademicWeekInfo? {
    if (semesterStartStr == null || !isYmdString(semesterStartStr) || !isYmdString(dateStr)) return null
    val start = parseIsoDate(semesterStartStr) ?: return null
    val target = parseIsoDate(dateStr) ?: return null
    fun monday(d: LocalDate): LocalDate { val day = d.dayOfWeek.daysSinceSunday; return d.plus(DatePeriod(days = 1 - (if (day == 0) 7 else day))) }
    val diffDays = monday(start).daysUntil(monday(target))
    val week = diffDays.floorDiv(7) + 1
    return if (week < 1) AcademicWeekInfo(week, "Pre-semester", false) else AcademicWeekInfo(week, "Week $week", true)
}

// ---------------------------------------------------------------------------
// Attendance
// ---------------------------------------------------------------------------

data class AttendanceStats(
    val totalSessions: Int,
    val presentCount: Int,
    val absentCount: Int,
    val excusedCount: Int,
    val cancelledCount: Int,
    val unmarkedCount: Int,
    /** present + absent */
    val activeCount: Int,
    /**
     * Sessions attendance is measured against: everything except cancelled,
     * excused and still-future unmarked sessions. Unmarked sessions up to today
     * DO count, so a forgotten mark never leaves a flattering 100%.
     */
    val countedSessions: Int,
    val attendancePercent: Double,
    val absencesRemaining: Int?,
    val isBelowMinPercent: Boolean,
    val isOverAbsenceLimit: Boolean,
)

enum class AttendanceRiskTier { Unknown, Safe, Warning, Critical }

/**
 * THE single source of truth for a course's tracker key: the base code (or the
 * base title for short/absent codes) plus the semester. Any other derivation
 * silently forks the tracker.
 */
fun resolveCourseKey(identity: CourseIdentity): String =
    TrackerCourses.courseKey(identity.courseCode, identity.courseName, identity.semester)

fun resolveCourseKey(event: ScheduleEvent): String = TrackerCourses.courseKey(event.courseCode, event.courseName, event.semester)

/** Collapses duplicate sessions of a slot, keeping a marked one; sorted by date and time. */
fun deduplicateSessions(sessions: List<AttendanceSession>): List<AttendanceSession> {
    val map = LinkedHashMap<String, AttendanceSession>()
    for (s in sessions) {
        if (s.date.isEmpty()) continue
        val date = s.date.trim()
        val time = s.startTime.orEmpty().trim()
        val key = if (time.isNotEmpty()) "${date}_$time" else "${date}_${s.sessionType.wire}"
        val existing = map[key]
        map[key] = when {
            existing == null -> s
            existing.status == AttendanceStatus.Unmarked && s.status != AttendanceStatus.Unmarked -> s.copy(
                startTime = s.startTime.nonEmpty() ?: existing.startTime,
                endTime = s.endTime.nonEmpty() ?: existing.endTime,
                title = s.title.ifEmpty { existing.title },
            )
            else -> existing.copy(
                startTime = existing.startTime.nonEmpty() ?: s.startTime,
                endTime = existing.endTime.nonEmpty() ?: s.endTime,
            )
        }
    }
    return map.values.sortedWith(SessionOrder)
}

/**
 * The attendance session type an event generates: explicit attendance_type
 * wins, then the group code's class type (E01 → lecture, G01 → seminar,
 * L01 → lab), then the event type.
 */
fun getEventSessionType(courseCode: String?, eventType: String?, attendanceType: String?): SessionType {
    val explicit = attendanceType.nonEmpty()
    var type = if (explicit == "unknown") SessionType.Other else SessionType.Lecture
    when (explicit) {
        "seminar" -> type = SessionType.Seminar
        "lab" -> type = SessionType.Lab
        "lecture" -> type = SessionType.Lecture
        null -> {
            val label = TrackerCourses.classKind(courseCode.nonEmpty() ?: eventType)?.label?.uppercase().orEmpty()
            type = when {
                label == "SEM" || label == "SEMINAR" -> SessionType.Seminar
                label == "LAB" || label == "LABORATORY" -> SessionType.Lab
                eventType == "seminar" -> SessionType.Seminar
                eventType == "lab" -> SessionType.Lab
                else -> type
            }
        }
    }
    return type
}

fun getEventSessionType(event: ScheduleEvent): SessionType = getEventSessionType(event.courseCode, event.eventType, event.attendanceType)

private data class ClassSlot(
    val dayOfWeek: Int,
    val startTime: String?,
    val endTime: String?,
    val sessionType: SessionType,
    val frequency: String?,
    val startDate: String?,
    val endDate: String?,
    /** Manual weekly classes skip public holidays; Neptun ranges are already split around them. */
    val skipHolidays: Boolean,
)

/** Weeks generated when no slot has an end date (a manual class without a semester end). */
private const val TotalWeeks = 14

/** Upper bound for a dated course, so a malformed far-future end date can't run away. */
private const val MaxWeeks = 53

/**
 * Weeks from [start] needed to reach the latest Neptun slot's end date: at least [TotalWeeks], and
 * never more than [MaxWeeks]. A Neptun course whose own span is longer than 14 weeks (a 15-week
 * semester, a meeting in the week before classes, a make-up class after the last week) would
 * otherwise silently lose its last weeks. Only Neptun slots count (`!skipHolidays`): their end is
 * the last real meeting, while a manual class's end can be a semester or exam-period end that
 * would add weeks the class never meets.
 */
private fun weeksToCover(start: LocalDate, slots: List<ClassSlot>): Int {
    val last = slots
        .filter { !it.skipHolidays }
        .mapNotNull { slot -> slot.endDate?.takeIf { YmdPattern.matches(it) }?.let(::parseIsoDate) }
        .maxOrNull()
        ?: return TotalWeeks
    val weeks = start.daysUntil(last) / 7 + 1
    return weeks.coerceIn(TotalWeeks, MaxWeeks)
}

/**
 * Generates the semester's sessions for a course from its schedule events (or
 * the manual fallback slot), preserving every marked or custom session and
 * never resurrecting a tombstoned slot.
 */
fun generateSessionsFromScheduleEvents(
    courseName: String,
    courseCode: String? = null,
    semester: String? = null,
    dayOfWeek: Int? = null,
    startTime: String? = null,
    endTime: String? = null,
    eventType: String? = null,
    semesterStart: String? = null,
    semesterEnd: String? = null,
    frequency: String? = null,
    scheduleEvents: List<ScheduleEvent> = emptyList(),
    existingSessions: List<AttendanceSession> = emptyList(),
    removedSessionKeys: List<String> = emptyList(),
    today: LocalDate = nowInBudapest().date,
): List<AttendanceSession> {
    val normSemester = semester.orEmpty().lowercase().trim()
    val removed = removedSessionKeys.toSet()
    fun sameCourse(e: ScheduleEvent) = TrackerCourses.isSameCourse(courseName, courseCode, e.courseName, e.courseCode)
    // Exams never become attendance sessions (also exams stored as 'class').
    val hasMatchingExam = scheduleEvents.any { it.isExam() && sameCourse(it) }
    var matching = scheduleEvents.filter { !it.isExam() && sameCourse(it) }
    if (normSemester.isNotEmpty()) {
        val semMatches = matching.filter { it.semester.lowercase().trim() == normSemester }
        if (semMatches.isNotEmpty()) matching = semMatches
    }

    val slots = mutableListOf<ClassSlot>()
    val seen = HashSet<String>()
    if (matching.isNotEmpty()) {
        for (evt in matching) {
            val day = evt.dayOfWeek
            val sTime = normalizeTime(evt.startTime).orEmpty()
            val eTime = normalizeTime(evt.endTime).orEmpty()
            val sDate = evt.semesterStart.nonEmpty()?.trim()
            val eDate = evt.semesterEnd.nonEmpty()?.trim()
            val type = getEventSessionType(evt)
            val key = "${day}_${sTime}_${type.wire}_${sDate.orEmpty()}_${eDate.orEmpty()}"
            if (seen.add(key)) {
                slots += ClassSlot(
                    dayOfWeek = day,
                    startTime = sTime.nonEmpty(),
                    endTime = eTime.nonEmpty(),
                    sessionType = type,
                    frequency = getEventFrequency(evt.csvMetadata, acceptJsonString = true).key,
                    startDate = sDate.nonEmpty() ?: semesterStart.nonEmpty(),
                    endDate = eDate.nonEmpty() ?: semesterEnd.nonEmpty(),
                    skipHolidays = evt.source != "neptun",
                )
            }
        }
    } else if (dayOfWeek != null && !hasMatchingExam) {
        slots += ClassSlot(
            dayOfWeek = dayOfWeek,
            startTime = startTime,
            endTime = endTime,
            sessionType = SessionType.fromWire(eventType) ?: SessionType.Lecture,
            frequency = frequency,
            startDate = semesterStart.nonEmpty(),
            endDate = semesterEnd.nonEmpty(),
            skipHolidays = true,
        )
    }

    // No slots → only the student's own marked/custom sessions; never phantom ones.
    if (slots.isEmpty()) {
        val preserved = existingSessions.filter { it.date.isNotEmpty() && (it.isCustom == true || it.status != AttendanceStatus.Unmarked) }
        return deduplicateSessions(preserved.filter { sessionSlotKey(it) !in removed })
    }

    val validStarts = (listOf(semesterStart) + slots.map { it.startDate })
        .mapNotNull { it.nonEmpty() }
        .filter { YmdPattern.matches(it) }
        .sorted()
    val start: LocalDate = validStarts.firstOrNull()?.let(::parseIsoDate) ?: LocalDate(today.year, today.month, 1)

    // Existing lookup: id, date+time+type, date+time, and — for untimed
    // sessions only — date+type / date. Loose keys must never match a timed
    // session, or marking one intensive slot would copy onto its siblings.
    val existingMap = HashMap<String, AttendanceSession>()
    for (s in existingSessions) {
        if (s.date.isEmpty()) continue
        val date = s.date.trim()
        val time = s.startTime.orEmpty().trim()
        val type = s.sessionType.wire
        if (s.id.isNotEmpty()) existingMap[s.id] = s
        if (time.isNotEmpty()) {
            existingMap["${date}_${time}_$type"] = s
            existingMap["${date}_$time"] = s
        } else {
            existingMap["${date}_$type"] = s
            if (!existingMap.containsKey(date)) existingMap[date] = s
        }
    }

    val result = mutableListOf<AttendanceSession>()
    val generated = HashSet<String>()
    val startDay = start.dayOfWeek.daysSinceSunday
    for (week in 1..weeksToCover(start, slots)) {
        for (slot in slots) {
            val offset = (slot.dayOfWeek - startDay + 7) % 7
            val dateStr = start.plus(DatePeriod(days = offset + (week - 1) * 7)).ymd()
            if (slot.frequency == "even" && week % 2 != 0) continue
            if (slot.frequency == "odd" && week % 2 == 0) continue
            if (slot.startDate != null && dateStr < slot.startDate) continue
            if (slot.endDate != null && dateStr > slot.endDate) continue
            if (slot.skipHolidays && isHungarianPublicHoliday(dateStr)) continue
            val fullKey = "${dateStr}_${slot.startTime.orEmpty()}_${slot.sessionType.wire}"
            if (!generated.add(fullKey)) continue
            val existing = existingMap[fullKey]
                ?: existingMap["${dateStr}_${slot.startTime.orEmpty()}"]
                ?: existingMap["${dateStr}_${slot.sessionType.wire}"]
                ?: existingMap[dateStr]
            result += AttendanceSession(
                id = existing?.id.nonEmpty() ?: genId(),
                date = dateStr,
                startTime = slot.startTime.nonEmpty() ?: existing?.startTime,
                endTime = slot.endTime.nonEmpty() ?: existing?.endTime,
                title = existing?.title.nonEmpty() ?: "Week $week",
                sessionType = slot.sessionType,
                status = existing?.status ?: AttendanceStatus.Unmarked,
                notes = existing?.notes,
                isCustom = existing?.isCustom,
            )
        }
    }

    // Keep marked or custom sessions that were not part of the standard slots.
    for (s in existingSessions) {
        if (s.date.isEmpty()) continue
        val included = result.any {
            it.id == s.id || (it.date == s.date && it.startTime.orEmpty() == s.startTime.orEmpty() && it.sessionType == s.sessionType)
        }
        if (!included && (s.isCustom == true || s.status != AttendanceStatus.Unmarked)) result += s
    }
    return deduplicateSessions(result.sortedWith(SessionOrder).filter { sessionSlotKey(it) !in removed })
}

/** generateScheduleSessions: the single-slot convenience form. */
fun generateScheduleSessions(
    dayOfWeek: Int,
    startTime: String? = null,
    endTime: String? = null,
    semesterStart: String? = null,
    semesterEnd: String? = null,
    sessionType: SessionType? = null,
    frequency: String? = null,
    existingSessions: List<AttendanceSession> = emptyList(),
    removedSessionKeys: List<String> = emptyList(),
): List<AttendanceSession> = generateSessionsFromScheduleEvents(
    courseName = "Course",
    dayOfWeek = dayOfWeek,
    startTime = startTime,
    endTime = endTime,
    eventType = sessionType?.wire,
    existingSessions = existingSessions,
    removedSessionKeys = removedSessionKeys,
    semesterStart = semesterStart,
    semesterEnd = semesterEnd,
    frequency = frequency,
)

fun calculateAttendanceStats(
    sessions: List<AttendanceSession>,
    maxAbsencesAllowed: Int?,
    minAttendancePercent: Double?,
    /** Budapest date (YYYY-MM-DD); defaults to today in Budapest. */
    today: String = nowInBudapest().ymd,
): AttendanceStats {
    var present = 0
    var absent = 0
    var excused = 0
    var cancelled = 0
    var unmarked = 0
    var futureUnmarked = 0
    for (s in sessions) {
        when (s.status) {
            AttendanceStatus.Present -> present++
            AttendanceStatus.Absent -> absent++
            AttendanceStatus.Excused -> excused++
            AttendanceStatus.Cancelled -> cancelled++
            AttendanceStatus.Unmarked -> {
                unmarked++
                if (s.date.isNotEmpty() && s.date > today) futureUnmarked++
            }
        }
    }
    val counted = sessions.size - cancelled - excused - futureUnmarked
    val percent = if (counted > 0) present.toDouble() / counted * 100 else 0.0
    return AttendanceStats(
        totalSessions = sessions.size,
        presentCount = present,
        absentCount = absent,
        excusedCount = excused,
        cancelledCount = cancelled,
        unmarkedCount = unmarked,
        activeCount = present + absent,
        countedSessions = counted,
        attendancePercent = percent,
        absencesRemaining = maxAbsencesAllowed?.let { maxOf(0, it - absent) },
        isBelowMinPercent = minAttendancePercent?.let { percent < it && counted > 0 } ?: false,
        isOverAbsenceLimit = maxAbsencesAllowed?.let { absent > it } ?: false,
    )
}

/**
 * Risk tier: unknown with nothing marked; critical over the quota or below the
 * minimum; warning at the quota or one away; safe otherwise.
 */
fun getAttendanceRiskTier(
    absentCount: Int,
    maxAbsencesAllowed: Int?,
    attendancePercent: Double,
    minAttendancePercent: Double?,
    activeCount: Int,
): AttendanceRiskTier {
    if (activeCount == 0) return AttendanceRiskTier.Unknown
    if (maxAbsencesAllowed != null && absentCount > maxAbsencesAllowed) return AttendanceRiskTier.Critical
    if (minAttendancePercent != null && attendancePercent < minAttendancePercent) return AttendanceRiskTier.Critical
    if (maxAbsencesAllowed != null && maxAbsencesAllowed > 0 && absentCount == maxAbsencesAllowed) return AttendanceRiskTier.Warning
    if (maxAbsencesAllowed != null && maxAbsencesAllowed > 1 && absentCount == maxAbsencesAllowed - 1) return AttendanceRiskTier.Warning
    return AttendanceRiskTier.Safe
}
