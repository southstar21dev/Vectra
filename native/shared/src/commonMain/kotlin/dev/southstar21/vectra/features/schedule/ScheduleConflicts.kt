package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.time.formatMinuteOfDay
import dev.southstar21.vectra.core.time.isValidCalendarDate
import dev.southstar21.vectra.core.time.daysSinceSunday
import dev.southstar21.vectra.features.holidays.isHungarianPublicHoliday
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** A personal schedule item: a work shift or a task. */
@Serializable
data class ScheduleItem(
    val id: String,
    /** 'shift' | 'task' */
    val type: String,
    val title: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    val location: String? = null,
    val completed: Boolean? = null,
    val acknowledgedConflictKeys: List<String> = emptyList(),
    /** True only for the second timeline segment of an overnight shift. */
    val continuation: Boolean? = null,
) {
    val isTask: Boolean get() = type == TYPE_TASK
    val isShift: Boolean get() = type == TYPE_SHIFT

    companion object {
        const val TYPE_SHIFT = "shift"
        const val TYPE_TASK = "task"
    }
}

/** A half-open interval in Budapest calendar minutes on [date]. */
data class ScheduleInterval(val date: String, val startMinute: Int, val endMinute: Int)

data class ScheduleConflict(
    val key: String,
    val itemId: String,
    val otherItemId: String? = null,
    val classId: String? = null,
    val classKey: String? = null,
    val otherTitle: String,
    /** 'shift' | 'task' | 'class' | 'exam' */
    val otherType: String,
    val date: String,
    val startMinute: Int,
    val endMinute: Int,
    val needsAcknowledgment: Boolean,
)

private const val MinutesPerDay = 24 * 60
private val ValidTime = Regex("""^(\d{2}):(\d{2})$""")
private val KeyWhitespace = Regex("""\s+""")

private fun ordinalForDate(value: String): Long? =
    if (!isValidCalendarDate(value)) null else LocalDate.parse(value).toEpochDays()

private fun formatDateOrdinal(ordinal: Long): String = LocalDate.fromEpochDays(ordinal).toString()

private fun weekdayForDate(value: String): Int? =
    if (!isValidCalendarDate(value)) null else LocalDate.parse(value).dayOfWeek.daysSinceSunday

private fun minuteOfDay(value: String): Int? {
    val m = ValidTime.find(value) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    if (h > 23 || min > 59) return null
    return h * 60 + min
}

/** The date-specific intervals an item occupies; an overnight shift splits at midnight. */
fun getItemIntervals(item: ScheduleItem): List<ScheduleInterval> {
    val ordinal = ordinalForDate(item.date)
    val start = minuteOfDay(item.startTime)
    val end = minuteOfDay(item.endTime)
    if (ordinal == null || start == null || end == null || start == end) return emptyList()
    if (end > start) return listOf(ScheduleInterval(item.date, start, end))
    if (item.type != ScheduleItem.TYPE_SHIFT) return emptyList()
    val out = mutableListOf<ScheduleInterval>()
    if (start < MinutesPerDay) out += ScheduleInterval(item.date, start, MinutesPerDay)
    if (end > 0) out += ScheduleInterval(formatDateOrdinal(ordinal + 1), 0, end)
    return out
}

private fun occurrenceInterval(event: ScheduleEvent): Pair<Int, Int>? {
    val start = minuteOfDay(event.startTime)
    val end = minuteOfDay(event.endTime)
    if (start == null || end == null || end <= start) return null
    return start to end
}

/** True when the class/exam is a mandatory-attendance conflict candidate. */
fun isConflictRelevantClass(event: ScheduleEvent, intensive: Boolean = false): Boolean {
    if (event.isExam() || intensive || isIntensiveClass(event.courseName, event.courseCode, event.eventType)) return true
    // Non-Neptun rows are manual; an unclassified one is treated as mandatory.
    if (event.source != "neptun") {
        return event.attendanceType?.ifEmpty { null }?.let { it != "lecture" } ?: true
    }
    when (getClassType(event.courseCode.ifEmpty { event.eventType })?.label) {
        "Intensive" -> return true
        "Lecture" -> return false
        "Seminar", "Lab" -> return true
    }
    if (event.eventType == "lecture") return false
    return true
}

/**
 * What occurrence checks need from one list of events, memoized. Asking whether a class
 * meets on a date compares it against every event of its course, so answering that for
 * each event, per date, per item is otherwise quadratic in regex-heavy string work.
 * Confined to one computation: it is not thread-safe.
 */
internal class ScheduleEventIndex(events: List<ScheduleEvent>) {
    private class Slot(val names: CourseNames, val semester: String, val start: String?)

    private data class CourseKey(val name: String, val code: String, val semester: String)

    private val validDates = HashMap<String, Boolean>()
    private val courseNames = HashMap<Pair<String, String>, CourseNames>()
    private val exams = HashMap<Pair<String, String>, Boolean>()
    private val earliestStarts = HashMap<CourseKey, String?>()
    private val slots: List<Slot> = events.filter { !isExam(it) }.map {
        Slot(names(it), it.semester.trim().lowercase(), it.semesterStart?.takeIf(::isValid))
    }

    fun isValid(date: String): Boolean = validDates.getOrPut(date) { isValidCalendarDate(date) }

    fun isExam(event: ScheduleEvent): Boolean =
        exams.getOrPut(event.eventType to event.courseName) { event.isExam() }

    private fun names(event: ScheduleEvent) =
        courseNames.getOrPut(event.courseName to event.courseCode) { ScheduleCourses.names(event.courseName, event.courseCode) }

    /** Odd/even weeks anchor at the earliest start among the course's slots. */
    fun earliestStart(event: ScheduleEvent): String? =
        earliestStarts.getOrPut(CourseKey(event.courseName, event.courseCode, event.semester)) {
            val names = names(event)
            var matching = slots.filter { names.sameCourse(it.names) }
            val semester = event.semester.trim().lowercase()
            if (semester.isNotEmpty()) {
                val sameSemester = matching.filter { it.semester == semester }
                if (sameSemester.isNotEmpty()) matching = sameSemester
            }
            matching.mapNotNull { it.start }.minOrNull()
        }
}

private fun semesterWeekOneStart(event: ScheduleEvent, referenceDate: String, index: ScheduleEventIndex): String? {
    index.earliestStart(event)?.let { return it }
    // First-of-month fallback, anchored to the reference date so it stays deterministic.
    if (!index.isValid(referenceDate)) return null
    return referenceDate.substring(0, 8) + "01"
}

/** Whether a weekly class meets on a candidate Budapest-local date. */
fun classOccursOnDate(
    event: ScheduleEvent,
    dateStr: String,
    referenceDate: String = dateStr,
    scheduleEvents: List<ScheduleEvent> = listOf(event),
): Boolean = classOccursOnDate(event, dateStr, referenceDate, ScheduleEventIndex(scheduleEvents))

/** [classOccursOnDate] for many events of one list, which share [index]. */
internal fun classOccursOnDate(event: ScheduleEvent, dateStr: String, referenceDate: String, index: ScheduleEventIndex): Boolean {
    val candidateOrdinal = ordinalForDate(dateStr) ?: return false
    val candidateWeekday = LocalDate.fromEpochDays(candidateOrdinal).dayOfWeek.daysSinceSunday
    occurrenceInterval(event) ?: return false

    val dateStart = event.semesterStart?.takeIf(index::isValid)
    val dateEnd = event.semesterEnd?.takeIf(index::isValid)
    if (index.isExam(event)) return dateStart == dateStr && dateEnd == dateStr
    if (candidateWeekday != event.dayOfWeek) return false
    if (dateStart != null && dateStr < dateStart) return false
    if (dateEnd != null && dateStr > dateEnd) return false
    // Manual weekly classes repeat blindly, but nobody teaches on a public holiday.
    if (event.source != "neptun" && isHungarianPublicHoliday(dateStr)) return false

    val weekOneStart = semesterWeekOneStart(event, referenceDate, index) ?: return false
    val weekOneOrdinal = ordinalForDate(weekOneStart) ?: return false
    val anchorWeekday = weekdayForDate(weekOneStart) ?: return false
    val firstOccurrence = weekOneOrdinal + ((event.dayOfWeek - anchorWeekday + 7) % 7)
    val since = candidateOrdinal - firstOccurrence
    if (since < 0 || since % 7 != 0L) return false
    val weekNumber = since / 7 + 1
    return when (getEventFrequency(event.csvMetadata)) {
        ScheduleFrequency.Even -> weekNumber % 2 == 0L
        ScheduleFrequency.Odd -> weekNumber % 2 != 0L
        ScheduleFrequency.Weekly -> true
    }
}

private fun normalizeKeyPart(value: String): String = value.trim().lowercase().replace(KeyWhitespace, " ")

/** Stable occurrence key that survives Neptun's replace-all row ids. */
fun getClassConflictKey(event: ScheduleEvent, date: String): String = listOf(
    "class",
    event.source ?: "manual",
    normalizeKeyPart(event.courseCode),
    normalizeKeyPart(event.courseName),
    normalizeKeyPart(event.semester),
    date,
    event.startTime.take(5),
    event.endTime.take(5),
    if (event.isExam()) "exam" else "class",
).joinToString(":")

private fun itemPairConflictKey(a: ScheduleItem, b: ScheduleItem, date: String, start: Int, end: Int): String {
    val ids = listOf(a.id, b.id).sorted()
    fun parts(i: ScheduleItem) = getItemIntervals(i).filter { it.date == date }.map { "${it.startMinute}-${it.endMinute}" }.sorted().joinToString(",")
    val intervals = listOf(parts(a), parts(b)).sorted()
    return "items:${ids[0]}:${ids[1]}:${intervals[0]}:${intervals[1]}:$date:$start-$end"
}

private fun overlap(a: ScheduleInterval, b: ScheduleInterval): Pair<Int, Int>? {
    if (a.date != b.date) return null
    val s = maxOf(a.startMinute, b.startMinute)
    val e = minOf(a.endMinute, b.endMinute)
    return if (s < e) s to e else null
}

/** Active clashes between personal items and class occurrences (optionally only on [dates]). */
fun getScheduleConflicts(
    items: List<ScheduleItem>,
    events: List<ScheduleEvent>,
    referenceDate: String,
    dates: Collection<String>? = null,
): List<ScheduleConflict> {
    val allowed = dates?.toSet()
    val conflicts = mutableListOf<ScheduleConflict>()
    val index = ScheduleEventIndex(events)
    val intensiveKeys = ScheduleCourses.intensiveCourseKeys(events.filter { !index.isExam(it) })
    val relevant = events.filter { isConflictRelevantClass(it, it.scheduleCourseKey() in intensiveKeys) }
    val active = items.filter { !(it.isTask && it.completed == true) }
    val intervals = active.associate { it.id to getItemIntervals(it) }

    for (i in active.indices) {
        val item = active[i]
        for (j in i + 1 until active.size) {
            val other = active[j]
            for (a in intervals[item.id].orEmpty()) {
                if (allowed != null && a.date !in allowed) continue
                for (b in intervals[other.id].orEmpty()) {
                    val (s, e) = overlap(a, b) ?: continue
                    val key = itemPairConflictKey(item, other, a.date, s, e)
                    val acknowledged = key in item.acknowledgedConflictKeys || key in other.acknowledgedConflictKeys
                    conflicts += ScheduleConflict(
                        key = key, itemId = item.id, otherItemId = other.id, otherTitle = other.title,
                        otherType = other.type, date = a.date, startMinute = s, endMinute = e, needsAcknowledgment = !acknowledged,
                    )
                }
            }
        }
    }

    for (item in active) {
        for (iv in intervals[item.id].orEmpty()) {
            if (allowed != null && iv.date !in allowed) continue
            for (event in relevant) {
                if (!classOccursOnDate(event, iv.date, referenceDate, index)) continue
                val (es, ee) = occurrenceInterval(event) ?: continue
                val (s, e) = overlap(iv, ScheduleInterval(iv.date, es, ee)) ?: continue
                val key = getClassConflictKey(event, iv.date)
                conflicts += ScheduleConflict(
                    key = key, itemId = item.id, classId = event.id, classKey = key, otherTitle = event.courseName,
                    otherType = if (index.isExam(event)) "exam" else "class", date = iv.date, startMinute = s, endMinute = e,
                    needsAcknowledgment = key !in item.acknowledgedConflictKeys,
                )
            }
        }
    }

    return conflicts.sortedWith(compareBy<ScheduleConflict> { it.date }.thenBy { it.startMinute }.thenBy { it.itemId })
}

fun conflictIsAcknowledged(conflict: ScheduleConflict, items: List<ScheduleItem>): Boolean {
    val target = items.firstOrNull { it.id == conflict.itemId }
    if (target != null && conflict.key in target.acknowledgedConflictKeys) return true
    val otherId = conflict.otherItemId ?: return false
    val other = items.firstOrNull { it.id == otherId }
    return other != null && conflict.key in other.acknowledgedConflictKeys
}

// ---- Occurrences --------------------------------------------------------------

data class ScheduleItemOccurrence(
    val item: ScheduleItem,
    val date: String,
    val startTime: String,
    val endTime: String,
    val continuation: Boolean,
)

/** Expand items into day-specific timeline rows for an inclusive date range. */
fun getScheduleItemOccurrences(items: List<ScheduleItem>, startDate: String, endDate: String): List<ScheduleItemOccurrence> {
    if (!isValidCalendarDate(startDate) || !isValidCalendarDate(endDate) || startDate > endDate) return emptyList()
    val out = mutableListOf<ScheduleItemOccurrence>()
    for (item in items) {
        for (iv in getItemIntervals(item)) {
            if (iv.date in startDate..endDate) {
                out += ScheduleItemOccurrence(item, iv.date, formatMinuteOfDay(iv.startMinute), formatMinuteOfDay(iv.endMinute), iv.date != item.date)
            }
        }
    }
    return out.sortedWith(compareBy<ScheduleItemOccurrence> { it.date }.thenBy { it.startTime }.thenBy { it.item.id })
}

/** A day's item rows, including the next-day tail of an overnight shift. */
fun getScheduleItemOccurrencesForDate(items: List<ScheduleItem>, date: String): List<ScheduleItemOccurrence> =
    getScheduleItemOccurrences(items, date, date)
