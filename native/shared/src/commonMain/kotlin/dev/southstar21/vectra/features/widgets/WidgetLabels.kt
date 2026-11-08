package dev.southstar21.vectra.features.widgets

import dev.southstar21.vectra.core.time.daysSinceSunday
import dev.southstar21.vectra.core.time.formatBudapestHm
import dev.southstar21.vectra.core.time.parseYmd
import kotlinx.datetime.number

// The widgets' wording; iOS mirrors it in iosApp/VectraWidgets/Model.swift.

fun clockLabel(ms: Long): String = formatBudapestHm(ms)

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

private val ShortDays = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val ShortMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
