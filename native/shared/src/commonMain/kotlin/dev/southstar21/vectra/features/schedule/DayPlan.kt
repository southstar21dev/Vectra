package dev.southstar21.vectra.features.schedule

/**
 * The pure layout of one day's list: which entries have finished (they fold into the stack),
 * where the break strips go, which strip is current, and which entries clash.
 */

private val ClockTime = Regex("""^\s*(\d{1,2}):(\d{1,2})""")

/** "HH:mm" (or "HH:mm:ss") → minute of the day; "24:00" is 1440. */
internal fun minuteOf(time: String?): Int? {
    val match = time?.let { ClockTime.find(it) } ?: return null
    val (h, m) = match.destructured
    val minute = h.toInt() * 60 + m.toInt()
    return minute.takeIf { m.toInt() < 60 && it <= 24 * 60 }
}

/** One class or personal item on the day, with its span in minutes. */
internal sealed interface DayEntry {
    val key: String
    val start: Int
    val end: Int
    val title: String

    /** A cancelled entry stays in place but frees its time: it never splits a break or clashes. */
    val cancelled: Boolean

    data class Class(val event: ScheduleEvent, override val start: Int, override val end: Int, override val cancelled: Boolean) : DayEntry {
        override val key get() = "c:${event.id}:${event.startTime}"
        override val title get() = event.courseName
    }

    data class Item(val occurrence: ScheduleItemOccurrence, override val start: Int, override val end: Int) : DayEntry {
        override val key get() = "i:${occurrence.item.id}:${occurrence.date}:${occurrence.startTime}"
        override val title get() = occurrence.item.title
        override val cancelled get() = false
    }
}

internal enum class BreakState { Now, Next, Later }

internal sealed interface DayRow {
    val key: String

    data class Entry(val entry: DayEntry) : DayRow {
        override val key get() = entry.key
    }

    data class Break(val start: Int, val end: Int, val state: BreakState) : DayRow {
        override val key get() = "b:$start-$end"
        val minutes get() = end - start
    }
}

/** The earlier entry a later one overlaps, and the overlapping span. */
internal data class Overlap(val otherTitle: String, val start: Int, val end: Int)

internal data class DayPlan(
    /** Entries whose end has passed, oldest first. Empty unless the list collapses them. */
    val finished: List<DayEntry>,
    val rows: List<DayRow>,
    /** Entry key → what it clashes with. Only the later of two clashing entries is marked. */
    val overlaps: Map<String, Overlap>,
    /** The class to check in to next: the one on now, else the next one. */
    val focusKey: String?,
) {
    val entryCount get() = finished.size + rows.count { it is DayRow.Entry }

    /** Everything is over: the stack and the closing card are all that's left. */
    val dayIsOver get() = finished.isNotEmpty() && rows.none { it is DayRow.Entry }
}

/** Gaps shorter than this get no strip: the cards just follow each other. */
internal const val MinBreakMinutes = 5

/**
 * [nowMinute] is the minute of the day when the list shows today, else null. With
 * [collapseFinished], entries that have ended move to [DayPlan.finished] and the breaks
 * between them go with them.
 */
internal fun buildDayPlan(entries: List<DayEntry>, nowMinute: Int?, collapseFinished: Boolean): DayPlan {
    val sorted = entries.sortedWith(
        compareBy<DayEntry> { it.start }.thenBy { if (it is DayEntry.Class) 0 else 1 }.thenBy { it.end }.thenBy { it.title },
    )
    val finished = if (collapseFinished && nowMinute != null) sorted.filter { it.end <= nowMinute } else emptyList()
    val remaining = sorted - finished.toSet()
    val hidePast = collapseFinished && nowMinute != null
    val rows = placeRows(remaining, startCursor = finished.filter { !it.cancelled }.maxOfOrNull { it.end }, nowMinute.takeIf { hidePast })
    return DayPlan(finished, withBreakStates(rows, nowMinute), findOverlaps(sorted), focusKeyOf(remaining, nowMinute))
}

/**
 * The entries in order with a break strip wherever a gap of [MinBreakMinutes] or more opens.
 * A break goes straight after the last entry that holds time, before any cancelled ones, so a
 * cancelled class never splits it. Breaks already over by [pastBefore] are left out.
 */
private fun placeRows(remaining: List<DayEntry>, startCursor: Int?, pastBefore: Int?): List<DayRow> {
    val rows = mutableListOf<DayRow>()
    var cursor = startCursor
    var insertAt = 0
    for (e in remaining) {
        if (e.cancelled) {
            rows += DayRow.Entry(e)
            continue
        }
        val from = cursor
        val past = pastBefore != null && e.start <= pastBefore
        if (from != null && e.start - from >= MinBreakMinutes && !past) {
            rows.add(insertAt, DayRow.Break(from, e.start, BreakState.Later))
        }
        rows += DayRow.Entry(e)
        insertAt = rows.size
        cursor = maxOf(cursor ?: e.end, e.end)
    }
    return rows
}

/** The break you're in is "now"; otherwise the first one still ahead is "next". */
private fun withBreakStates(rows: List<DayRow>, nowMinute: Int?): List<DayRow> {
    if (nowMinute == null) return rows
    val nowIndex = rows.indexOfFirst { it is DayRow.Break && nowMinute in it.start until it.end }
    val nextIndex = if (nowIndex >= 0) -1 else rows.indexOfFirst { it is DayRow.Break && it.start >= nowMinute }
    return rows.mapIndexed { i, row ->
        when {
            row !is DayRow.Break -> row
            i == nowIndex -> row.copy(state = BreakState.Now)
            i == nextIndex -> row.copy(state = BreakState.Next)
            else -> row
        }
    }
}

/** Classes and shifts that run at the same time; tasks are deadlines, not places to be. */
private fun findOverlaps(sorted: List<DayEntry>): Map<String, Overlap> {
    val timed = sorted.filter { !it.cancelled && !(it is DayEntry.Item && it.occurrence.item.isTask) }
    val overlaps = LinkedHashMap<String, Overlap>()
    for ((i, later) in timed.withIndex()) {
        val earlier = timed.subList(0, i).lastOrNull { it.end > later.start && it.start < later.end } ?: continue
        overlaps[later.key] = Overlap(earlier.title, maxOf(earlier.start, later.start), minOf(earlier.end, later.end))
    }
    return overlaps
}

/** The class to check in to next: the one on now, else the next one (never an exam or a cancelled one). */
private fun focusKeyOf(remaining: List<DayEntry>, nowMinute: Int?): String? {
    if (nowMinute == null) return null
    return remaining.firstOrNull { it is DayEntry.Class && !it.cancelled && !it.event.isExam() && it.end > nowMinute }?.key
}

/** "15 min", "1 h 15 min", "2 h". */
internal fun durationLabel(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}

/** Which colour the time left in the current break takes. */
internal enum class TimeLeftTone { Plenty, Soon, Urgent }

/** More than 30 min: plenty; 10–30: soon; 10 or less: urgent. */
internal fun timeLeftTone(minutesLeft: Int): TimeLeftTone = when {
    minutesLeft > 30 -> TimeLeftTone.Plenty
    minutesLeft > 10 -> TimeLeftTone.Soon
    else -> TimeLeftTone.Urgent
}
