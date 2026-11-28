@file:OptIn(ExperimentalSerializationApi::class, ExperimentalUuidApi::class)

package dev.southstar21.vectra.features.coursetracker

import dev.southstar21.vectra.features.schedule.normalizeCourseKey
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val LooseTime = Regex("""^(\d{1,2}):(\d{1,2})""")

/**
 * The course tracker's stored JSON. The whole document is one encrypted blob
 * per user and its shape must stay exact: optional keys stay absent (stored
 * readers reject null for them) while the nullable-required attendance limits
 * are written as explicit nulls.
 */
@Serializable
internal enum class AttendanceStatus {
    @SerialName("present") Present,
    @SerialName("absent") Absent,
    @SerialName("excused") Excused,
    @SerialName("cancelled") Cancelled,
    @SerialName("unmarked") Unmarked,
}

@Serializable
internal enum class SessionType(val wire: String) {
    @SerialName("lecture") Lecture("lecture"),
    @SerialName("seminar") Seminar("seminar"),
    @SerialName("lab") Lab("lab"),
    @SerialName("consultation") Consultation("consultation"),
    @SerialName("exam") Exam("exam"),
    @SerialName("other") Other("other"),
    ;

    companion object {
        fun fromWire(value: String?): SessionType? = entries.firstOrNull { it.wire == value }
    }
}

/** Default course total points — the denominator of the Points Earned card. */
internal const val DefaultCourseTotalPoints = 100.0

/**
 * Identifies a course wherever it is seen (schedule event, check-in, Course
 * Hub). Codes may carry a group suffix (`X5VX18SUBM01M-G10`); the canonical key strips it.
 */
internal data class CourseIdentity(
    val courseCode: String? = null,
    val courseName: String? = null,
    val semester: String? = null,
    val instructor: String? = null,
    val location: String? = null,
    val color: String? = null,
)

@Serializable
internal data class AttendanceSession(
    val id: String,
    /** ISO 'YYYY-MM-DD'. */
    val date: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val startTime: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val endTime: String? = null,
    val title: String,
    val sessionType: SessionType,
    val status: AttendanceStatus,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val notes: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val isCustom: Boolean? = null,
    /** ISO instant of the last status change, so a merge keeps the newer of two devices' marks. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val markedAt: String? = null,
)

/**
 * Canonical slot key (`date_HH:MM_type`). Remembers deleted generated sessions
 * so the reconciler never resurrects them.
 */
internal fun sessionSlotKey(date: String, startTime: String?, sessionType: SessionType): String {
    val raw = (startTime ?: "").trim()
    val m = LooseTime.find(raw)
    val time = if (m != null) "${m.groupValues[1].padStart(2, '0')}:${m.groupValues[2].padStart(2, '0')}" else ""
    return "${date.trim()}_${time}_${sessionType.wire}"
}

internal fun sessionSlotKey(session: AttendanceSession): String = sessionSlotKey(session.date, session.startTime, session.sessionType)

@Serializable
internal data class GradingScaleTier(val grade: Int, val minPercentage: Double)

@Serializable
internal data class AssessmentItem(
    val id: String,
    val name: String,
    val earnedPoints: Double,
    val maxPoints: Double,
    val weightPercent: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val isMustPass: Boolean? = null,
    val minPassPoints: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val isCompleted: Boolean? = null,
    val date: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val notes: String? = null,
)

@Serializable
internal data class TrackerAttendance(
    val maxAbsencesAllowed: Int?,
    val minAttendancePercent: Double?,
    val sessions: List<AttendanceSession>,
    /** Slot keys of generated sessions the student deleted; the reconciler skips them. */
    val removedSessionKeys: List<String> = emptyList(),
)

@Serializable
internal data class TrackerPoints(
    val useWeightedCategories: Boolean = false,
    val gradingScale: List<GradingScaleTier>,
    val assessments: List<AssessmentItem>,
    /** The course's total points (default 100) — the Points Earned denominator. */
    val totalPoints: Double = DefaultCourseTotalPoints,
)

@Serializable
internal data class CourseTracker(
    val id: String,
    val courseKey: String,
    val courseName: String,
    val courseCode: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val instructor: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val location: String? = null,
    val semester: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val color: String? = null,
    /**
     * True once the student confirmed the limits and scale against the syllabus;
     * until then seeded defaults must never masquerade as the course's rules.
     */
    val settingsConfirmed: Boolean = false,
    val attendance: TrackerAttendance,
    val points: TrackerPoints,
    val updatedAt: String,
)

@Serializable
internal data class CourseTrackerData(
    val version: Int,
    val trackers: Map<String, CourseTracker>,
)

internal val DefaultGradingScale: List<GradingScaleTier> = listOf(
    GradingScaleTier(5, 87.0),
    GradingScaleTier(4, 75.0),
    GradingScaleTier(3, 62.0),
    GradingScaleTier(2, 50.0),
    GradingScaleTier(1, 0.0),
)

/** Seeded attendance limits — defaults to confirm, never asserted facts. */
internal object DefaultAttendanceLimits {
    const val MaxAbsencesAllowed = 3
    val MinAttendancePercent: Double? = null
}

/** genId(): crypto.randomUUID. */
internal fun genId(): String = Uuid.random().toString()

internal fun nowIso(): String = Clock.System.now().toString()

internal fun createDefaultTracker(
    courseName: String,
    courseCode: String? = null,
    semester: String? = null,
    instructor: String? = null,
    location: String? = null,
    color: String? = null,
): CourseTracker {
    val code = courseCode.orEmpty()
    val sem = semester.orEmpty()
    return CourseTracker(
        id = genId(),
        courseKey = normalizeCourseKey(code.ifEmpty { courseName }, sem),
        courseName = courseName.trim(),
        courseCode = code,
        instructor = instructor,
        location = location,
        semester = sem,
        color = color,
        settingsConfirmed = false,
        attendance = TrackerAttendance(DefaultAttendanceLimits.MaxAbsencesAllowed, DefaultAttendanceLimits.MinAttendancePercent, emptyList(), emptyList()),
        points = TrackerPoints(false, DefaultGradingScale, emptyList(), DefaultCourseTotalPoints),
        updatedAt = nowIso(),
    )
}

internal fun createEmptyTrackerData() = CourseTrackerData(1, emptyMap())

internal val TrackerJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
    coerceInputValues = false
}

/** Schema constraints; any violation rejects the whole document. */
private fun CourseTracker.isValid(): Boolean {
    if (id.isEmpty() || courseKey.isEmpty() || courseName.isEmpty()) return false
    attendance.maxAbsencesAllowed?.let { if (it < 0) return false }
    attendance.minAttendancePercent?.let { if (it !in 0.0..100.0) return false }
    if (attendance.sessions.any { it.id.isEmpty() }) return false
    if (points.gradingScale.any { it.grade !in 1..5 || it.minPercentage !in 0.0..100.0 }) return false
    if (points.totalPoints !in 1.0..10000.0) return false
    for (a in points.assessments) {
        if (a.id.isEmpty() || a.name.isEmpty() || a.earnedPoints < 0 || a.maxPoints < 0) return false
        a.weightPercent?.let { if (it !in 0.0..100.0) return false }
        a.minPassPoints?.let { if (it < 0) return false }
    }
    return true
}

/**
 * parseTrackerData: validates the document and collapses sessions sharing a
 * slot (date + start + type), keeping the marked one, sorted by date and time.
 */
internal fun parseTrackerData(raw: String?): CourseTrackerData? {
    if (raw.isNullOrEmpty()) return null
    val parsed = runCatching { TrackerJson.decodeFromString(CourseTrackerData.serializer(), raw) }.getOrNull() ?: return null
    if (parsed.version != 1 || parsed.trackers.values.any { !it.isValid() }) return null
    val trackers = parsed.trackers.mapValues { (_, tracker) ->
        val seen = LinkedHashMap<String, AttendanceSession>()
        for (s in tracker.attendance.sessions) {
            if (s.date.isEmpty()) continue
            val key = "${s.date.trim()}_${s.startTime.orEmpty()}_${s.sessionType.wire}"
            val existing = seen[key]
            seen[key] = when {
                existing == null -> s
                existing.status == AttendanceStatus.Unmarked && s.status != AttendanceStatus.Unmarked ->
                    s.copy(startTime = s.startTime.nonEmpty() ?: existing.startTime, endTime = s.endTime.nonEmpty() ?: existing.endTime)
                else -> existing.copy(startTime = existing.startTime.nonEmpty() ?: s.startTime, endTime = existing.endTime.nonEmpty() ?: s.endTime)
            }
        }
        tracker.copy(attendance = tracker.attendance.copy(sessions = seen.values.sortedWith(SessionOrder)))
    }
    return parsed.copy(trackers = trackers)
}

internal fun serializeTrackerData(data: CourseTrackerData): String = TrackerJson.encodeToString(CourseTrackerData.serializer(), data)

/** `x || y` for optional strings: empty reads as missing. */
internal fun String?.nonEmpty(): String? = this?.takeIf { it.isNotEmpty() }

/** Date, then start time (missing start sorts first). */
internal val SessionOrder: Comparator<AttendanceSession> = compareBy<AttendanceSession> { it.date }.thenBy { it.startTime.orEmpty() }
