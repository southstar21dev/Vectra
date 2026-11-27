package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.time.nowInBudapest
import kotlin.time.Clock
import kotlin.time.Instant

// ---- Semester status banner ---------------------------------------------------

/** status: 'current' | 'active' | 'upcoming' | 'past' */
internal data class SemesterInfo(
    val status: String,
    val startDate: String? = null,
    val daysUntil: Int = 0,
)

/** Whether the semester banner has anything to say (so an empty Reveal adds no gap). */
internal fun shouldShowSemesterStatus(info: SemesterInfo, selectedSemester: String): Boolean =
    info.status == "upcoming" || (info.status == "past" && selectedSemester != "all")

// ---- Semester tags ------------------------------------------------------------

/** Corvinus semester tag for a year and month (1–12): Aug–Jan → "/1", Feb–Jul → "/2". */
internal fun getSemesterFromYearMonth(year: Int, month: Int): String = when {
    month in 8..12 -> "$year/${(year + 1).toString().takeLast(2)}/1"
    month == 1 -> "${year - 1}/${year.toString().takeLast(2)}/1"
    else -> "${year - 1}/${year.toString().takeLast(2)}/2"
}

/** The semester in session by the Budapest wall clock. */
internal fun getCurrentSemester(at: Instant = Clock.System.now()): String {
    val now = nowInBudapest(at)
    return getSemesterFromYearMonth(now.year, now.month)
}

// ---- Semester selection -------------------------------------------------------

internal data class SemesterTaggedEvent(val semester: String, val semesterEnd: String? = null)

/**
 * The one semester a Neptun sync imports (wizard and background sync agree):
 * the current one while it is not over, else the nearest upcoming, else the
 * ended current one. A stored semester is never replaced by an older pick.
 */
internal fun selectSyncSemester(
    events: List<SemesterTaggedEvent>,
    at: Instant = Clock.System.now(),
    storedSemester: String? = null,
): String? {
    val today = nowInBudapest(at).ymd
    val current = getCurrentSemester(at)
    val feed = events.map { it.semester }.filter { it.isNotEmpty() }.distinct().sorted()
    fun lastDay(semester: String): String =
        events.filter { it.semester == semester && !it.semesterEnd.isNullOrEmpty() }
            .fold("") { max, e -> if (e.semesterEnd!! > max) e.semesterEnd else max }

    val hasCurrent = current in feed
    val currentOngoing = hasCurrent && (lastDay(current) == "" || lastDay(current) >= today)
    val upcoming = feed.firstOrNull { it > current }

    val pick = when {
        currentOngoing -> current
        upcoming != null -> upcoming
        hasCurrent -> current
        else -> null
    }
    val stored = storedSemester?.ifEmpty { null }
    if (stored != null && (pick == null || pick < stored)) {
        return if (stored in feed) stored else null
    }
    return pick
}

/** Newest semester among stored Neptun events, or null. */
internal fun newestStoredSemester(events: List<ScheduleEvent>): String? =
    events.filter { it.source == "neptun" && it.semester.isNotEmpty() }.map { it.semester }.maxOrNull()
