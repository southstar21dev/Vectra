package dev.southstar21.vectra.features.widgets

// The week widget's timetable. iOS mirrors this in iosApp/VectraWidgets/Spine.swift (weekGrid).

/** A class block in the week timetable, in minutes since midnight. */
data class WeekBlock(val kind: WidgetKind, val startMin: Int, val endMin: Int, val label: String, val done: Boolean)

data class WeekColumn(val day: WeekDay, val blocks: List<WeekBlock>)

/** The week timetable: its columns and the hours it spans (whole hours, at least six). */
data class WeekGrid(val columns: List<WeekColumn>, val fromHour: Int, val toHour: Int)

fun WidgetSnapshot?.weekGrid(today: String, nowMs: Long, minuteOfDay: (Long) -> Int): WeekGrid {
    val sessions = this?.takeIf { it.signedIn }?.sessions.orEmpty()
    val columns = week(today).map { d ->
        val blocks = sessions.filter { it.day == d.date && it.kind.isTimed }.map {
            val start = minuteOfDay(it.startMs)
            val end = (start + ((it.endMs - it.startMs) / MinuteMs).toInt()).coerceAtMost(24 * 60)
            WeekBlock(it.kind, start, end, courseAbbrev(it.title), it.endMs <= nowMs)
        }
        WeekColumn(d, blocks)
    }
    val all = columns.flatMap { it.blocks }
    var from = all.minOfOrNull { it.startMin / 60 } ?: 8
    var to = all.maxOfOrNull { (it.endMin + 59) / 60 } ?: 18
    if (to - from < 6) {
        to = (from + 6).coerceAtMost(24)
        from = (to - 6).coerceAtLeast(0)
    }
    return WeekGrid(columns, from, to)
}

/** Whether the week shown has nothing on Saturday or Sunday. */
fun WeekGrid.weekendFree(): Boolean = columns.size <= 5
