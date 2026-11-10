package dev.southstar21.vectra.features.reminders

import dev.southstar21.vectra.core.time.Budapest
import dev.southstar21.vectra.core.time.ymd
import dev.southstar21.vectra.features.coursetracker.SessionType
import dev.southstar21.vectra.features.coursetracker.getEventSessionType
import dev.southstar21.vectra.features.schedule.ScheduleEvent
import dev.southstar21.vectra.features.schedule.ScheduleEventIndex
import dev.southstar21.vectra.features.schedule.ScheduleItem
import dev.southstar21.vectra.features.schedule.classOccursOnDate
import dev.southstar21.vectra.features.schedule.getScheduleItemOccurrences
import dev.southstar21.vectra.features.schedule.isExam
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

enum class SessionKind { Lecture, Seminar, Lab, Exam, Personal, Other }

/** One class, exam or timed personal item on one day, in epoch millis. [key] ends in "@yyyy-mm-dd". */
data class ReminderSession(
    val key: String,
    val title: String,
    val location: String,
    val typeLabel: String?,
    val kind: SessionKind,
    val startMs: Long,
    val endMs: Long,
    /** The course colour (#rrggbb) for a class; empty otherwise. */
    val color: String = "",
) {
    val day: String get() = key.substringAfterLast('@')
}

/** A gap longer than this between sessions counts as going home and coming back. */
const val LongBreakMinutes = 120

internal const val MinuteMs = 60_000L

/** The kinds the type chips switch off; anything unclassified always counts. */
fun SessionKind.allowedBy(prefs: ReminderPrefs): Boolean = when (this) {
    SessionKind.Lecture -> prefs.lectures
    SessionKind.Seminar -> prefs.seminars
    SessionKind.Lab -> prefs.labs
    SessionKind.Exam -> prefs.exams
    SessionKind.Personal, SessionKind.Other -> true
}

/** Seminars and practice share a kind: a group code's practice classes read as seminars. */
fun sessionKind(event: ScheduleEvent): SessionKind = when {
    event.isExam() -> SessionKind.Exam
    else -> when (getEventSessionType(event)) {
        SessionType.Lecture -> SessionKind.Lecture
        SessionType.Seminar -> SessionKind.Seminar
        SessionType.Lab -> SessionKind.Lab
        else -> SessionKind.Other
    }
}

private fun SessionKind.label(): String? = when (this) {
    SessionKind.Lecture -> "Lecture"
    SessionKind.Seminar -> "Seminar"
    SessionKind.Lab -> "Lab"
    SessionKind.Exam -> "Exam"
    SessionKind.Personal, SessionKind.Other -> null
}

/** "08:15" or "08:15:00" on [date], Budapest wall clock → epoch millis. */
internal fun budapestMs(date: LocalDate, time: String): Long? {
    val parts = time.trim().split(':').map { it.toIntOrNull() }
    val h = parts.getOrNull(0)?.takeIf { it in 0..23 }
    val m = parts.getOrNull(1)?.takeIf { it in 0..59 }
    return if (h != null && m != null) {
        LocalDateTime(date.year, date.month, date.day, h, m).toInstant(Budapest).toEpochMilliseconds()
    } else null
}

/** Start and end on [date]; null unless both parse and the end comes after the start. */
private fun timeRange(date: LocalDate, start: String, end: String): Pair<Long, Long>? {
    val s = budapestMs(date, start)
    val e = budapestMs(date, end)
    return if (s != null && e != null && e > s) s to e else null
}

/**
 * The sessions that actually meet on each of [days] days from [from]: odd/even weeks, semester
 * ranges and public holidays as the schedule shows them, minus classes marked "No class".
 */
fun expandSessions(
    events: List<ScheduleEvent>,
    items: List<ScheduleItem>,
    includeItems: Boolean,
    from: LocalDate,
    days: Int,
    isCancelled: (ScheduleEvent, String) -> Boolean = { _, _ -> false },
): List<ReminderSession> {
    val index = ScheduleEventIndex(events)
    val classes = (0 until days).flatMap { offset ->
        val date = from.plus(DatePeriod(days = offset))
        val ymd = date.ymd()
        events
            .filter { classOccursOnDate(it, ymd, index) && !isCancelled(it, ymd) }
            .mapNotNull { e ->
                val kind = sessionKind(e)
                timeRange(date, e.startTime, e.endTime)?.let { (s, end) ->
                    ReminderSession("${e.id}@$ymd", e.courseName.trim(), e.location.trim(), kind.label(), kind, s, end, e.color.trim())
                }
            }
    }
    val personal = if (!includeItems || days <= 0) emptyList() else {
        getScheduleItemOccurrences(items, from.ymd(), from.plus(DatePeriod(days = days - 1)).ymd())
            .filter { !it.continuation && it.item.completed != true }
            .mapNotNull { o ->
                timeRange(LocalDate.parse(o.date), o.startTime, o.endTime)?.let { (s, end) ->
                    val label = if (o.item.isShift) "Shift" else "Task"
                    ReminderSession("${o.item.id}@${o.date}", o.item.title.trim(), o.item.location.orEmpty().trim(), label, SessionKind.Personal, s, end)
                }
            }
    }
    return (classes + personal).sortedWith(compareBy<ReminderSession> { it.startMs }.thenBy { it.endMs })
}

/** The sessions split by day, and within a day wherever the gap is long enough to go home. */
internal fun splitIntoStretches(sessions: List<ReminderSession>): List<List<ReminderSession>> {
    val stretches = mutableListOf<MutableList<ReminderSession>>()
    var runningEnd = Long.MIN_VALUE
    for (s in sessions) {
        val current = stretches.lastOrNull()
        val sameStretch = current != null && current.last().day == s.day && (s.startMs - runningEnd) / MinuteMs <= LongBreakMinutes
        if (sameStretch) {
            current.add(s)
            runningEnd = maxOf(runningEnd, s.endMs)
        } else {
            stretches += mutableListOf(s)
            runningEnd = s.endMs
        }
    }
    return stretches
}
