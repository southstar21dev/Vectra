package dev.southstar21.vectra.features.reminders

import dev.southstar21.vectra.core.platform.DepartureQuery
import dev.southstar21.vectra.core.time.formatBudapestHm

fun leadLabel(minutes: Int): String = when {
    minutes % 60 == 0 -> if (minutes == 60) "in 1 hour" else "in ${minutes / 60} hours"
    else -> "in $minutes min"
}

fun durationLabel(minutes: Long): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0L -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

/** "Tram 6 from Blaha Lujza tér: 09:08 · 09:13 · 09:18". */
fun departuresLine(query: DepartureQuery, times: List<Long>): String =
    if (times.isEmpty()) "${query.lineLabel} from ${query.stopName}: no departures found"
    else "${query.lineLabel} from ${query.stopName}: ${times.joinToString(" · ") { formatBudapestHm(it) }}"

/** The line without times, for when they can't be known in advance. */
fun departuresFallbackLine(query: DepartureQuery): String = "${query.lineLabel} from ${query.stopName} · tap for departures"

internal fun details(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")

/** A bare room code reads as "Room B-204"; a longer place name ("Q building, QBF08") stays as it is. */
internal fun roomOf(s: ReminderSession): String? = s.location.ifEmpty { null }?.let { if (it.length <= 8 && ' ' !in it) "Room $it" else it }
