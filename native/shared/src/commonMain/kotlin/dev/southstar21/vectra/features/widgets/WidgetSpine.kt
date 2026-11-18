package dev.southstar21.vectra.features.widgets

// The time-spine layout the widgets share: which rows a day shows, the week timetable's blocks and
// the short labels. iOS mirrors this in iosApp/VectraWidgets/Spine.swift; keep the two in step.

/** One row on the spine. */
sealed interface SpineRow {
    data class Past(val session: WidgetSession) : SpineRow

    data class Now(val session: WidgetSession) : SpineRow

    data class Next(val session: WidgetSession) : SpineRow

    data class Break(val minutes: Int) : SpineRow

    /** A later day's name, before its rows. */
    data class Day(val day: String) : SpineRow
}

/** The time a widget lists a session at: a task's deadline, anything else's start. */
val WidgetSession.listedMs: Long get() = if (kind == WidgetKind.Task) endMs else startMs

/** The room with the group after it: "E 201, G04". */
val WidgetSession.place: String get() = listOf(location, group).filter { it.isNotBlank() }.joinToString(", ")

/** The day a day-widget shows: today while anything is on it, else the next day that has something. */
fun WidgetSnapshot?.dayShown(today: String): String =
    if (sessionsOn(today).isNotEmpty()) today else this?.takeIf { it.signedIn }?.sessions.orEmpty().firstOrNull { it.day > today }?.day ?: today

/** [day]'s timed sessions as spine rows, with a break wherever there is real time between two. */
fun WidgetSnapshot?.spine(day: String, nowMs: Long): List<SpineRow> {
    val timed = sessionsOn(day).filter { it.kind.isTimed }
    val rows = mutableListOf<SpineRow>()
    timed.forEachIndexed { i, s ->
        if (i > 0) breakMinutes(timed[i - 1], s)?.let { rows += SpineRow.Break(it) }
        rows += when {
            s.endMs <= nowMs -> SpineRow.Past(s)
            s.startMs <= nowMs -> SpineRow.Now(s)
            else -> SpineRow.Next(s)
        }
    }
    return rows
}

/** The day's tasks still open at [nowMs]. */
fun WidgetSnapshot?.openTasks(day: String, nowMs: Long): List<WidgetSession> =
    sessionsOn(day).filter { it.kind == WidgetKind.Task && it.endMs > nowMs }

/** Timed sessions on [day] that are over at [nowMs], and all of them. */
fun WidgetSnapshot?.doneOf(day: String, nowMs: Long): Pair<Int, Int> {
    val timed = sessionsOn(day).filter { it.kind.isTimed }
    return timed.count { it.endMs <= nowMs } to timed.size
}

/** The exams after [exam], up to [limit]. */
fun WidgetSnapshot?.examsAfter(exam: WidgetSession, limit: Int): List<WidgetSession> =
    this?.sessions.orEmpty().filter { it.kind == WidgetKind.Exam && it.startMs > exam.startMs }.take(limit)

/**
 * A ring's big number and the word under it: "55" MIN under an hour, "2:25" (hours and minutes)
 * under a day, else whole days.
 */
fun ringLabel(ms: Long, hoursWord: String): Pair<String, String> {
    val minutes = ((ms + MinuteMs - 1) / MinuteMs).coerceAtLeast(0)
    return when {
        minutes < 60 -> "$minutes" to "MIN"
        minutes < 24 * 60 -> "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}" to hoursWord
        else -> {
            val days = (minutes + 24 * 60 - 1) / (24 * 60)
            "$days" to if (days == 1L) "DAY" else "DAYS"
        }
    }
}

private val MinorWords = setOf("and", "of", "the", "to", "in", "for", "a", "an", "és", "az")

/** A course's label for a small timetable block: "Data Structures" DS, "Microeconomics" MIC. */
fun courseAbbrev(title: String): String {
    val words = title.split(' ', '-', '/', ',').map { it.trim() }.filter { w -> w.isNotEmpty() && w.lowercase() !in MinorWords }
    val main = words.filter { w -> w.length >= 3 && w.first().isLetter() && !w.all { it.isUpperCase() && it in "IVX" } }
    return when {
        main.isEmpty() -> title.take(3).uppercase()
        main.size == 1 -> main.first().take(3).uppercase()
        else -> main.take(3).joinToString("") { it.first().uppercase() }
    }
}

