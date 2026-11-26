package dev.southstar21.vectra.features.widgets

import dev.southstar21.vectra.core.time.daysSinceSunday
import dev.southstar21.vectra.core.preferences.TimeFormat
import dev.southstar21.vectra.core.time.formatClock
import dev.southstar21.vectra.core.time.parseYmd
import kotlinx.datetime.number

// The widgets' wording; iOS mirrors it in iosApp/VectraWidgets/Model.swift.

/** "08:05", or "8:05 AM" when [hour12] (the snapshot's copy of the app's time format setting). */
fun clockLabel(ms: Long, hour12: Boolean = false): String =
    formatClock(ms, if (hour12) TimeFormat.H12 else TimeFormat.H24)

/** The week grid's hour axis: "08", or "8" and "2" (no AM or PM, the column is 16 dp) in 12-hour mode. */
fun hourAxisLabel(hour: Int, hour12: Boolean): String =
    if (hour12) ((hour % 12).let { if (it == 0) 12 else it }).toString() else hour.toString().padStart(2, '0')

/** "in 25 min", "in 1 h 40 min", "in 3 h". */
fun countdownLabel(nowMs: Long, startMs: Long): String = "in ${durationLabel(startMs - nowMs)}"

/** "70 min left", "1 h 10 min left". */
fun remainingLabel(nowMs: Long, endMs: Long): String = "${durationLabel(endMs - nowMs)} left"

private fun durationLabel(ms: Long): String {
    val minutes = ((ms + MinuteMs - 1) / MinuteMs).coerceAtLeast(1)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h h"
        else -> "$h h $m min"
    }
}

/** "Today", "Tomorrow", "In 3 days". */
fun dayDistanceLabel(today: String, day: String): String {
    val from = parseYmd(today)
    val to = parseYmd(day)
    if (from == null || to == null) return day
    return when (val days = (to.toEpochDays() - from.toEpochDays()).toInt()) {
        0 -> "Today"
        1 -> "Tomorrow"
        else -> "In $days days"
    }
}

/** "Thu" for a yyyy-mm-dd. */
fun weekdayLabel(day: String): String = parseYmd(day)?.let { ShortDays[it.dayOfWeek.daysSinceSunday] } ?: ""

/** "Tue 10 Nov". */
fun dayHeading(day: String): String =
    parseYmd(day)?.let { "${ShortDays[it.dayOfWeek.daysSinceSunday]} ${it.day} ${ShortMonths[it.month.number - 1]}" } ?: ""

/** "Wednesday" for a yyyy-mm-dd. */
fun weekdayLongLabel(day: String): String = parseYmd(day)?.let { LongDays[it.dayOfWeek.daysSinceSunday] } ?: ""

/** "18 Nov". */
fun dayMonthLabel(day: String): String = parseYmd(day)?.let { "${it.day} ${ShortMonths[it.month.number - 1]}" } ?: ""

/** "1 h 10 min", "45 min". */
fun spanLabel(ms: Long): String = durationLabel(ms)

private val LongDays = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
private val ShortDays = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val ShortMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
