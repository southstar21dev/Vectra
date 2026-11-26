package dev.southstar21.vectra.features.schedule

import androidx.compose.runtime.Composable
import dev.southstar21.vectra.core.designsystem.components.rememberPageClock

/** What a schedule card shows for a session; the course tracker maps its own status onto it. */
enum class CardAttendance { Present, Absent, Excused, Cancelled, Unmarked }

/**
 * The course tracker's touch points on the schedule: attendance on the cards and the
 * Course Hub. The course tracker implements this; the schedule never imports it.
 */
interface ScheduleCourseTrackerHooks {
    /** Status of the session of [event] on [date] (the card's activeSession lookup). */
    @Composable
    fun attendanceStatus(event: ScheduleEvent, date: String): CardAttendance

    fun setAttendanceStatus(event: ScheduleEvent, date: String, status: CardAttendance)

    /** The key the Course Hub for [event]'s course is pushed with. */
    fun courseKey(event: ScheduleEvent): String

    /**
     * [event]'s class attended in a row, counted back from its session on [date] (the check-in's
     * "+1, N in a row"): only that course's sessions of the same type (seminar, lecture, lab).
     */
    fun presentStreak(event: ScheduleEvent, date: String): Int
}

/** Minute clock: recomposes every minute so "now" states roll over. */
@Composable
internal fun rememberMinuteClock(): Long = rememberPageClock(60_000)
