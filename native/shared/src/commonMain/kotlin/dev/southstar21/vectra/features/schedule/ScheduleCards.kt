package dev.southstar21.vectra.features.schedule

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.southstar21.vectra.core.designsystem.components.rememberPageClock
import dev.southstar21.vectra.core.designsystem.theme.Palette

/** course-tracker AttendanceStatus */
enum class AttendanceStatus { Present, Absent, Excused, Cancelled, Unmarked }

/**
 * The course tracker's touch points on the schedule: attendance on the cards and the
 * Course Hub. The course tracker implements this; the schedule never imports it.
 */
interface ScheduleCourseTrackerHooks {
    /** Status of the session of [event] on [date] (the card's activeSession lookup). */
    @Composable
    fun attendanceStatus(event: ScheduleEvent, date: String): AttendanceStatus

    fun setAttendanceStatus(event: ScheduleEvent, date: String, status: AttendanceStatus)

    /** The key the Course Hub for [event]'s course is pushed with. */
    fun courseKey(event: ScheduleEvent): String

    /**
     * [event]'s class attended in a row, counted back from its session on [date] (the check-in's
     * "+1, N in a row"): only that course's sessions of the same type (seminar, lecture, lab).
     */
    fun presentStreak(event: ScheduleEvent, date: String): Int
}

/**
 * `#RRGGBB` (or `#RGB`) → opaque Color; [fallback] when unreadable. Unlike the
 * transit `parseHexColor`, an 8-digit value drops its alpha and nothing returns null.
 */
internal fun parseOpaqueHexColor(hex: String?, fallback: Color = Palette.Sky400): Color {
    val h = hex?.trim()?.removePrefix("#") ?: return fallback
    val full = when (h.length) {
        3 -> h.map { "$it$it" }.joinToString("")
        6 -> h
        8 -> h.take(6)
        else -> return fallback
    }
    val v = full.toLongOrNull(16) ?: return fallback
    return Color(0xFF000000 or v)
}

/** Minute clock: recomposes every minute so "now" states roll over. */
@Composable
internal fun rememberMinuteClock(): Long = rememberPageClock(60_000)
