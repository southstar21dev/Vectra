package dev.southstar21.vectra.features.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.preferences.TimeFormat
import dev.southstar21.vectra.core.time.nowInBudapest
import kotlin.time.Instant

private fun statusKey(e: ScheduleEvent) = "${e.id}:${e.startTime}"

/** The day's classes and items as list entries; anything without a readable time is left out. */
private fun dayEntries(events: List<ScheduleEvent>, items: List<ScheduleItemOccurrence>, statuses: Map<String, AttendanceStatus>): List<DayEntry> =
    events.mapNotNull { e ->
        val s = minuteOf(e.startTime)
        val end = minuteOf(e.endTime)
        if (s == null || end == null) null else DayEntry.Class(e, s, end, statuses[statusKey(e)] == AttendanceStatus.Cancelled)
    } + items.mapNotNull { o ->
        val s = minuteOf(o.startTime)
        val end = minuteOf(o.endTime)
        if (s == null || end == null) null else DayEntry.Item(o, s, end)
    }

/** Each class's status for the date: a composable read, so the list follows the tracker. */
@Composable
private fun rememberDayStatuses(events: List<ScheduleEvent>, tracker: ScheduleCourseTrackerHooks, dateStr: String): Map<String, AttendanceStatus> {
    val statuses = HashMap<String, AttendanceStatus>()
    for (e in events) key(e.id, e.startTime) { statuses[statusKey(e)] = tracker.attendanceStatus(e, dateStr) }
    return statuses
}

/** Where the list's cards send taps, and the conflicts they show. */
private class DayListCards(
    val timeFormat: TimeFormat,
    val intensiveCourses: Set<String>?,
    val onEventClick: ((ScheduleEvent) -> Unit)?,
    val onItemClick: ((ScheduleItem) -> Unit)?,
    val onToggleTaskComplete: ((ScheduleItem) -> Unit)?,
    val onResolveSyncConflict: ((id: String, resolution: String) -> Unit)?,
    val syncConflictIds: Set<String>,
    val needsAcknowledgmentIds: Set<String>,
    val classConflictsByEventId: Map<String, List<ScheduleConflict>>,
    val itemConflictsById: Map<String, List<ScheduleConflict>>,
    val selectedCourseKey: String?,
)

/** One day's state: its plan, the statuses, "now", and what a check-in does. */
private class DayListScope(
    val d: DayColors,
    val dateStr: String,
    val nowMinute: Int?,
    val statuses: Map<String, AttendanceStatus>,
    val plan: DayPlan,
    val tracker: ScheduleCourseTrackerHooks,
    val pendingSync: SnapshotStateMap<String, Boolean>,
    val offline: Boolean,
) {
    fun statusOf(e: ScheduleEvent) = statuses[statusKey(e)] ?: AttendanceStatus.Unmarked

    /** Check-ins are for today's classes, any time of the day; exams have none. */
    fun canCheckIn(e: DayEntry.Class) = nowMinute != null && !e.event.isExam()

    fun setStatus(e: DayEntry.Class, s: AttendanceStatus) {
        tracker.setAttendanceStatus(e.event, dateStr, s)
        if (offline) pendingSync[e.key] = true
    }

    fun classState(e: DayEntry.Class, classConflicts: Map<String, List<ScheduleConflict>>): ClassCardState {
        val focus = plan.focusKey == e.key
        val now = nowMinute
        return ClassCardState(
            entry = e,
            status = statusOf(e.event),
            focus = focus,
            live = focus && now != null && now >= e.start && now < e.end,
            overlap = plan.overlaps[e.key],
            repoClash = classConflicts["$dateStr:${e.event.id}"].orEmpty().isNotEmpty(),
            canCheckIn = canCheckIn(e),
            pendingSync = pendingSync[e.key] == true,
        )
    }

    /** A stacked card's disc: the class's status, a task's done state, nothing for a shift. */
    private fun stackStatus(e: DayEntry): AttendanceStatus? = when (e) {
        is DayEntry.Class -> if (e.event.isExam()) null else statusOf(e.event)
        is DayEntry.Item -> when {
            !e.occurrence.item.isTask -> null
            e.occurrence.item.completed == true -> AttendanceStatus.Present
            else -> AttendanceStatus.Unmarked
        }
    }

    fun stackActions(onToggleTask: ((ScheduleItem) -> Unit)?) = StackActions(
        statusOf = ::stackStatus,
        onDisc = { e ->
            when {
                e is DayEntry.Class && canCheckIn(e) -> { { setStatus(e, nextStatus(statusOf(e.event))) } }
                e is DayEntry.Item && e.occurrence.item.isTask && onToggleTask != null -> { { onToggleTask(e.occurrence.item) } }
                else -> null
            }
        },
    )

    /** "4 classes and a shift, all checked in" for the closing card. */
    fun summary(): String {
        val classes = plan.finished.filterIsInstance<DayEntry.Class>().filter { !it.cancelled }
        val trackable = classes.filter { !it.event.isExam() }
        val items = plan.finished.filterIsInstance<DayEntry.Item>()
        return daySummary(
            classes = classes.size,
            shifts = items.count { !it.occurrence.item.isTask },
            tasks = items.count { it.occurrence.item.isTask },
            checkedIn = trackable.count { statusOf(it.event) == AttendanceStatus.Present },
            trackable = trackable.size,
        )
    }
}

/**
 * A day's classes and personal items as separate cards with break strips between them, one
 * connector line behind them all. On today's dashboard ([collapseFinished]) classes that are
 * over fold into a stack at the top, and a closing card wraps the day up once everything is.
 * Used by the dashboard and the Schedule tab.
 */
@Composable
fun ScheduleDayList(
    events: List<ScheduleEvent>,
    tracker: ScheduleCourseTrackerHooks,
    dateStr: String,
    modifier: Modifier = Modifier,
    items: List<ScheduleItemOccurrence> = emptyList(),
    onEventClick: ((ScheduleEvent) -> Unit)? = null,
    onItemClick: ((ScheduleItem) -> Unit)? = null,
    onToggleTaskComplete: ((ScheduleItem) -> Unit)? = null,
    onResolveSyncConflict: ((id: String, resolution: String) -> Unit)? = null,
    syncConflictIds: Set<String> = emptySet(),
    needsAcknowledgmentIds: Set<String> = emptySet(),
    classConflictsByEventId: Map<String, List<ScheduleConflict>> = emptyMap(),
    itemConflictsById: Map<String, List<ScheduleConflict>> = emptyMap(),
    timeFormat: TimeFormat = Vectra.appearance.timeFormat,
    intensiveCourses: Set<String>? = null,
    collapseFinished: Boolean = false,
    offline: Boolean = false,
    /** The course open in the wide Schedule tab's side pane; its cards are outlined. */
    selectedCourseKey: String? = null,
    emptyPlaceholder: (@Composable () -> Unit)? = null,
) {
    val clock = rememberMinuteClock()
    val now = nowInBudapest(Instant.fromEpochMilliseconds(clock))
    val nowMinute = if (dateStr == now.ymd) now.minuteOfDay else null
    val statuses = rememberDayStatuses(events, tracker, dateStr)
    val entries = remember(events, items, statuses) { dayEntries(events, items, statuses) }
    if (entries.isEmpty()) {
        emptyPlaceholder?.invoke()
        return
    }
    val plan = remember(entries, nowMinute, collapseFinished) { buildDayPlan(entries, nowMinute, collapseFinished) }
    // Check-ins made offline show a clock until the connection is back.
    val pendingSync = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(offline) { if (!offline) pendingSync.clear() }
    val scope = DayListScope(rememberDayColors(), dateStr, nowMinute, statuses, plan, tracker, pendingSync, offline)
    val cards = DayListCards(
        timeFormat, intensiveCourses, onEventClick, onItemClick, onToggleTaskComplete, onResolveSyncConflict,
        syncConflictIds, needsAcknowledgmentIds, classConflictsByEventId, itemConflictsById, selectedCourseKey,
    )
    DayListBody(scope, cards, modifier)
}

@Composable
private fun DayListBody(scope: DayListScope, cards: DayListCards, modifier: Modifier = Modifier) {
    val plan = scope.plan
    var stackOpen by remember(scope.dateStr) { mutableStateOf(false) }
    val visible = plan.rows.size + (if (plan.finished.isNotEmpty()) 1 else 0)
    Column(
        modifier.fillMaxWidth().connectorLine(visible > 1 && !plan.dayIsOver, scope.d.line),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (plan.finished.isNotEmpty()) {
            FinishedStack(plan.finished, stackOpen, scope.d, cards.timeFormat, scope.stackActions(cards.onToggleTaskComplete), onToggle = { stackOpen = !stackOpen })
        }
        for (row in plan.rows) key(row.key) { DayRowView(row, scope, cards) }
        if (plan.dayIsOver) DayDoneCard(scope.summary(), scope.d)
    }
}

/** One line down the middle; the opaque cards hide it, so it shows only in the gaps. */
private fun Modifier.connectorLine(show: Boolean, color: Color): Modifier = drawBehind {
    val inset = 24.dp.toPx()
    if (show && size.height > inset * 2) {
        drawLine(color, Offset(size.width / 2f, inset), Offset(size.width / 2f, size.height - inset), 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun DayRowView(row: DayRow, scope: DayListScope, cards: DayListCards) {
    when (row) {
        is DayRow.Break -> BreakStrip(row, scope.nowMinute, scope.d, cards.timeFormat)
        is DayRow.Entry -> when (val e = row.entry) {
            is DayEntry.Class -> ClassCard(
                scope.classState(e, cards.classConflictsByEventId),
                scope.d,
                cards.timeFormat,
                cards.intensiveCourses,
                cards.onEventClick,
                streak = { scope.tracker.presentStreak(e.event, scope.dateStr) },
                onSetStatus = { scope.setStatus(e, it) },
                selected = cards.selectedCourseKey != null && scope.tracker.courseKey(e.event) == cards.selectedCourseKey,
            )
            is DayEntry.Item -> {
                val id = e.occurrence.item.id
                ItemCard(
                    e,
                    ItemConflicts(
                        overlap = scope.plan.overlaps[e.key],
                        conflicts = cards.itemConflictsById["${e.occurrence.date}:$id"].orEmpty(),
                        syncConflict = id in cards.syncConflictIds,
                        waitingForAck = id in cards.needsAcknowledgmentIds,
                    ),
                    scope.d,
                    cards.timeFormat,
                    cards.onItemClick,
                    cards.onToggleTaskComplete,
                    cards.onResolveSyncConflict?.let { cb -> { r: String -> cb(id, r) } },
                )
            }
        }
    }
}
