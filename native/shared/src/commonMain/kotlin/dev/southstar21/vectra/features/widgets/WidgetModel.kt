package dev.southstar21.vectra.features.widgets

import dev.southstar21.vectra.core.time.parseYmd
import dev.southstar21.vectra.core.time.ymd
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

// What each widget shows, decided from a snapshot and the time. Android's Glance widgets use this
// directly; iOS mirrors it in Swift (iosApp/VectraWidgets/Model.swift).

internal const val MinuteMs = 60_000L
private const val HourMs = 60 * MinuteMs
private const val DayMs = 24 * HourMs

/** An exam this close gets the "next class" widgets once the day's classes are over. */
const val ExamHeadsUpMs = 7 * DayMs

/** What the "next class" widgets lead with. */
sealed interface NextUp {
    /** Signed out, or the app hasn't written a snapshot yet. */
    data object NoData : NextUp

    data class InClass(val session: WidgetSession) : NextUp

    data class Upcoming(val session: WidgetSession) : NextUp

    data class ExamSoon(val exam: WidgetSession) : NextUp

    /** Nothing left today. [next] is the first session on a later day, when the snapshot has one. */
    data class DayDone(val hadToday: Boolean, val next: WidgetSession?) : NextUp
}

fun WidgetSnapshot?.nextUp(nowMs: Long, today: String): NextUp {
    val snap = this?.takeIf { it.signedIn } ?: return NextUp.NoData
    val timed = snap.sessions.filter { it.kind.isTimed }
    val current = timed.firstOrNull { it.day == today && it.startMs <= nowMs && nowMs < it.endMs }
    val upcoming = timed.firstOrNull { it.day == today && it.startMs > nowMs }
    val later = timed.filter { it.day > today }
    val exam = later.firstOrNull { it.kind == WidgetKind.Exam && it.startMs - nowMs <= ExamHeadsUpMs }
    return when {
        current != null -> NextUp.InClass(current)
        upcoming != null -> NextUp.Upcoming(upcoming)
        exam != null -> NextUp.ExamSoon(exam)
        else -> NextUp.DayDone(hadToday = timed.any { it.day == today }, next = later.firstOrNull())
    }
}

/** The session [nextUp] leads with, if it leads with one from today. */
val NextUp.todaySession: WidgetSession?
    get() = when (this) {
        is NextUp.InClass -> session
        is NextUp.Upcoming -> session
        else -> null
    }

/** The session to feature: today's, or else the next one on a later day (an exam close by first). */
val NextUp.featured: WidgetSession?
    get() = when (this) {
        is NextUp.InClass -> session
        is NextUp.Upcoming -> session
        is NextUp.ExamSoon -> exam
        is NextUp.DayDone -> next
        NextUp.NoData -> null
    }

/** Everything on [today], in order. */
fun WidgetSnapshot?.sessionsOn(today: String): List<WidgetSession> =
    this?.takeIf { it.signedIn }?.sessions.orEmpty().filter { it.day == today }

/** What is still to come on [day] after [shown] (the session a widget already features). */
fun WidgetSnapshot?.laterOn(day: String, nowMs: Long, shown: WidgetSession?): List<WidgetSession> =
    sessionsOn(day).filter { it != shown && it.endMs > nowMs && (shown == null || it.startMs >= shown.startMs) }

data class WeekDay(
    val date: String,
    val weekday: String,
    val dayOfMonth: Int,
    val kinds: List<WidgetKind>,
    val isToday: Boolean,
    val isPast: Boolean,
)

/** Whether [week] shows next week: at the weekend this week's classes are over. */
fun showsNextWeek(today: String): Boolean =
    parseYmd(today)?.dayOfWeek.let { it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY }

/** Monday to Friday (of next week at the weekend), plus a weekend day when something is on it. */
fun WidgetSnapshot?.week(today: String): List<WeekDay> {
    val todayDate = parseYmd(today) ?: return emptyList()
    val monday = mondayOf(todayDate).plus(DatePeriod(days = if (showsNextWeek(today)) 7 else 0))
    val sessions = this?.takeIf { it.signedIn }?.sessions.orEmpty()
    return (0 until 7).mapNotNull { offset ->
        val date = monday.plus(DatePeriod(days = offset))
        val ymd = date.ymd()
        val kinds = sessions.filter { it.day == ymd && it.kind != WidgetKind.Task }.map { it.kind }
        if (offset >= 5 && kinds.isEmpty()) return@mapNotNull null
        WeekDay(ymd, weekdayLabel(ymd), date.day, kinds, ymd == today, ymd < today)
    }
}

/** Classes (not personal items) on the days [week] shows. */
fun List<WeekDay>.classCount(): Int = sumOf { day -> day.kinds.count { it.isClass } }

val WidgetKind.isClass: Boolean
    get() = this == WidgetKind.Lecture || this == WidgetKind.Seminar || this == WidgetKind.Lab || this == WidgetKind.Other

/** The first exam from [today] on within this week, if any. */
fun WidgetSnapshot?.examThisWeek(today: String): WidgetSession? {
    val days = week(today).map { it.date }.toSet()
    return this?.sessions.orEmpty().firstOrNull { it.kind == WidgetKind.Exam && it.day >= today && it.day in days }
}

/**
 * When the widgets should redraw next: the next session start or end, every five minutes while a
 * countdown or a progress bar is showing, and midnight at the latest.
 */
fun WidgetSnapshot?.nextRefreshMs(nowMs: Long, midnightMs: Long): Long {
    val sessions = this?.sessions.orEmpty()
    val boundary = sessions.flatMap { listOf(it.startMs, it.endMs) }.filter { it > nowMs }.minOrNull() ?: Long.MAX_VALUE
    val counting = sessions.any { (it.startMs > nowMs && it.startMs - nowMs <= 2 * HourMs) || (it.startMs <= nowMs && nowMs < it.endMs) }
    val tick = if (counting) nowMs + 5 * MinuteMs else Long.MAX_VALUE
    return minOf(boundary, tick, midnightMs)
}

/** 0..1 through [session] at [nowMs]. */
fun progress(session: WidgetSession, nowMs: Long): Float =
    ((nowMs - session.startMs).toFloat() / (session.endMs - session.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)

/** Minutes of free time between two consecutive sessions, when it's long enough to call a break. */
fun breakMinutes(before: WidgetSession, after: WidgetSession): Int? {
    val gap = ((after.startMs - before.endMs) / MinuteMs).toInt()
    return gap.takeIf { it >= 15 }
}

internal fun mondayOf(date: LocalDate): LocalDate =
    date.minus(DatePeriod(days = date.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber))
