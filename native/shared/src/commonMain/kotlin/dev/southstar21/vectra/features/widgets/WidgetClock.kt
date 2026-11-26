package dev.southstar21.vectra.features.widgets

import dev.southstar21.vectra.core.preferences.TimeFormat
import dev.southstar21.vectra.core.time.formatClock

// The widgets' clock times; iOS mirrors them in iosApp/VectraWidgets/Model.swift and NextClass.swift.

/** "08:05", or "8:05 AM" when [hour12] (the snapshot's copy of the app's time format setting). */
fun clockLabel(ms: Long, hour12: Boolean = false): String =
    formatClock(ms, if (hour12) TimeFormat.H12 else TimeFormat.H24)

/** The week grid's hour axis: "08", or "8" and "2" (no AM or PM, the column is 16 dp) in 12-hour mode. */
fun hourAxisLabel(hour: Int, hour12: Boolean): String =
    if (hour12) ((hour % 12).let { if (it == 0) 12 else it }).toString() else hour.toString().padStart(2, '0')

/**
 * The time beside the small widgets' ring as two lines, for when one line does not fit even at its
 * smallest size: a range breaks before its dash ("10:00 AM" over "–11:30 AM"), anything else after
 * its first word ("till" over "10:45 PM", "Thu" over "8:00 AM").
 */
fun stackedSideTime(text: String): List<String> {
    val dash = text.indexOf('–')
    if (dash > 0) return listOf(text.substring(0, dash), text.substring(dash))
    val space = text.indexOf(' ')
    return if (space > 0) listOf(text.substring(0, space), text.substring(space + 1)) else listOf(text)
}
