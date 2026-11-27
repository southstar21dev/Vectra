package dev.southstar21.vectra.features.widgets

// The lists the widgets assemble before they lay anything out: the Today widget's items, the
// agenda's footer lines and the small widget's "after" rows. Android's Glance widgets draw these
// directly; iOS mirrors them in Swift (DayWidgets.swift, NextClass.swift).

/** The Today widget's rows: a session, or the name of a later day. */
sealed interface TodayEntry {
    data class Session(val session: WidgetSession) : TodayEntry

    data class Day(val label: String) : TodayEntry
}

/** Most entries gathered before the widget stops adding later days. */
private const val TodayEntryLimit = 8

/** What's left today, then the days after, each under its name, until the list is full. */
fun WidgetSnapshot.todayEntries(today: String, nowMs: Long): List<TodayEntry> = buildList {
    addAll(sessionsOn(today).filter { it.endMs > nowMs }.map { TodayEntry.Session(it) })
    sessions.map { it.day }.filter { it > today }.distinct().forEach { day ->
        if (size >= TodayEntryLimit) return@forEach
        add(TodayEntry.Day("${dayDistanceLabel(today, day)}, ${dayHeading(day)}"))
        addAll(sessionsOn(day).map { TodayEntry.Session(it) })
    }
}

/** Where a footer line comes from, which decides its icon. */
enum class FootNoteType { Then, Task, NextDay }

/** A line under the large widget's rows: what it leaves off, an open task, or the next day. */
data class FootNote(val type: FootNoteType, val kind: WidgetKind?, val text: String, val time: String)

private fun andMore(count: Int) = if (count > 1) " and ${count - 1} more" else ""

/** "Then X and N more" for the sessions a cut leaves off. */
internal fun thenNote(hidden: List<WidgetSession>, hour12: Boolean = false): FootNote? = hidden.firstOrNull()?.let {
    FootNote(FootNoteType.Then, it.kind, "Then ${it.title}${andMore(hidden.size)}", clockLabel(it.startMs, hour12))
}

/** The first open task of [day], with how many more there are. */
fun WidgetSnapshot.taskNote(day: String, nowMs: Long): FootNote? {
    val tasks = openTasks(day, nowMs)
    return tasks.firstOrNull()?.let { FootNote(FootNoteType.Task, WidgetKind.Task, "${it.title}, due${andMore(tasks.size)}", clockLabel(it.endMs, hour12)) }
}

/** When no later day fits on the widget itself, the footer names the next one. */
fun WidgetSnapshot.nextDayNote(day: String, today: String): FootNote? =
    sessions.firstOrNull { it.day > day && it.kind.isTimed }?.let { n ->
        val count = sessionsOn(n.day).size
        FootNote(FootNoteType.NextDay, null, "${dayDistanceLabel(today, n.day)}, ${n.title}${andMore(count)}", clockLabel(n.startMs, hour12))
    }

/** The footer of [cut]: what it hides, the task line, and the next-day line when nothing later shows. */
fun footNotes(cut: AgendaCut, task: FootNote?, next: FootNote?, hour12: Boolean = false): List<FootNote> =
    listOfNotNull(thenNote(cut.hidden, hour12), task, next.takeIf { cut.shown.none { it is SpineRow.Day } && cut.hidden.isEmpty() })

/** A row of the small widget's list: a time, a title, a type dot. */
data class AfterRow(val time: String, val title: String, val kind: WidgetKind?)

/** The small widget's rows after [s]: the rest of its day, then the next days' first sessions with their weekday. */
fun WidgetSnapshot?.afterRows(s: WidgetSession, nowMs: Long, limit: Int = 4): List<AfterRow> {
    val hour12 = this?.hour12 == true
    val sameDay = laterOn(s.day, nowMs, s).map { AfterRow(clockLabel(it.listedMs, hour12), it.title, it.kind) }
    val later = this?.sessions.orEmpty().filter { it.day > s.day }.take(limit).map {
        AfterRow("${weekdayLabel(it.day)} ${clockLabel(it.listedMs, hour12)}", it.title, it.kind)
    }
    return (sameDay + later).take(limit)
}
