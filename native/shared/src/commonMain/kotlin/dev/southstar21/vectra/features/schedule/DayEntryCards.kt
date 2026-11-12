package dev.southstar21.vectra.features.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.ButtonSize
import dev.southstar21.vectra.core.designsystem.components.ButtonVariant
import dev.southstar21.vectra.core.designsystem.components.StatusTone
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.color
import dev.southstar21.vectra.core.preferences.TimeFormat
import dev.southstar21.vectra.core.time.formatMinuteOfDay
import dev.southstar21.vectra.core.time.formatTime

/** The type word and its colour tone, the same tones the class chips used before the redesign. */
private fun classTypeWord(event: ScheduleEvent, intensiveCourses: Set<String>?): Pair<String, StatusTone> {
    val intensive = intensiveCourses?.contains(event.scheduleCourseKey())
        ?: isIntensiveClass(event.courseName, event.courseCode, event.eventType)
    return when {
        event.isExam() -> "EXAM" to StatusTone.Violet
        intensive -> "INTENSIVE" to StatusTone.Amber
        !event.attendanceType.isNullOrEmpty() -> when (event.attendanceType) {
            "lecture" -> "LECTURE" to StatusTone.Sky
            "seminar" -> "SEMINAR" to StatusTone.Emerald
            "lab" -> "LAB" to StatusTone.Purple
            else -> "CLASS" to StatusTone.Neutral
        }
        event.source == "manual" -> "CLASS" to StatusTone.Neutral
        else -> getClassType(event.courseCode.ifEmpty { event.eventType })?.let { it.label.uppercase() to it.tone }
            ?: (event.eventType.ifEmpty { "Lecture" }.uppercase() to StatusTone.Sky)
    }
}

private fun overlapNote(o: Overlap): CardNote =
    CardNote("Overlaps ${o.otherTitle} ${formatMinuteOfDay(o.start)}–${formatMinuteOfDay(o.end % (24 * 60))}", alert = true)

/** One class card: what the list knows about it for this day. */
internal class ClassCardState(
    val entry: DayEntry.Class,
    val status: AttendanceStatus,
    val focus: Boolean,
    val live: Boolean,
    val overlap: Overlap?,
    val repoClash: Boolean,
    val canCheckIn: Boolean,
    val pendingSync: Boolean,
)

@Composable
internal fun ClassCard(
    state: ClassCardState,
    d: DayColors,
    timeFormat: TimeFormat,
    intensiveCourses: Set<String>?,
    onClick: ((ScheduleEvent) -> Unit)?,
    streak: () -> Int,
    onSetStatus: (AttendanceStatus) -> Unit,
    selected: Boolean = false,
) {
    val event = state.entry.event
    val raw = parseOpaqueHexColor(event.color.ifEmpty { "#38bdf8" })
    val course = readableOn(raw, d.card, d.isDark)
    val startTint = when {
        !state.focus -> d.text
        d.isDark -> lerp(raw, Color.White, 0.65f)
        else -> lerp(course, Color.Black, 0.15f)
    }
    val type = classTypeWord(event, intensiveCourses)
    // No "cancelled" line: it would push the orb down between taps of the status cycle; the stamp says it.
    val notes = listOfNotNull(
        state.overlap?.let(::overlapNote),
        CardNote("Checked in. Will sync when you are back online").takeIf { state.pendingSync && state.status != AttendanceStatus.Unmarked },
    )
    val content = DayCardContent(
        typeWord = type.first,
        typeColor = type.second.color(),
        title = event.courseName,
        subtitle = event.instructor,
        time = TimeBlock.Range(formatTime(event.startTime, timeFormat), formatTime(event.endTime, timeFormat)),
        startColor = startTint,
        cancelled = state.entry.cancelled,
        clash = state.overlap != null || state.repoClash,
        notes = notes,
        room = event.location,
        group = extractGroupCode(event.courseCode),
        clickLabel = "Open ${event.courseName}",
        selected = selected,
    )
    val look = OrbLook(course, state.focus, state.live, state.pendingSync, d.ring, d.card)
    DayCard(
        content,
        d,
        onClick = onClick?.let { { it(event) } },
        trailing = if (state.canCheckIn) {
            { CheckInOrb(state.status, event.courseName, look, streak, onSetStatus) }
        } else null,
    )
}

/** An item card's clash and sync state. */
internal class ItemConflicts(
    val overlap: Overlap?,
    val conflicts: List<ScheduleConflict>,
    val syncConflict: Boolean,
    val waitingForAck: Boolean,
)

/** Shift: start and end (an overnight first evening shows its real end with "+1"); task: due time or all day. */
private fun itemTime(entry: DayEntry.Item, timeFormat: TimeFormat): TimeBlock {
    val occ = entry.occurrence
    val item = occ.item
    fun clock(t: String) = formatTime(if (t == "24:00") "00:00" else t, timeFormat)
    val realEnd = minuteOf(item.endTime)
    val nextDay = !occ.continuation && entry.end == 24 * 60 && realEnd != null && realEnd < entry.start
    return when {
        item.isTask && entry.start == 0 && entry.end >= 24 * 60 -> TimeBlock.AllDay
        item.isTask -> TimeBlock.Due(clock(occ.endTime))
        nextDay -> TimeBlock.Range(clock(occ.startTime), clock(item.endTime), nextDay = true)
        else -> TimeBlock.Range(clock(occ.startTime), clock(occ.endTime))
    }
}

private fun itemNotes(c: ItemConflicts): List<CardNote> {
    val pending = c.conflicts.any { it.needsAcknowledgment }
    val repoOverlap = c.conflicts.firstOrNull()?.let { Overlap(it.otherTitle, it.startMinute, it.endMinute) }
    return listOfNotNull(
        (c.overlap ?: repoOverlap?.takeIf { pending })?.let(::overlapNote),
        CardNote("Open this item to review and acknowledge the clash", alert = true).takeIf { pending || c.waitingForAck },
        CardNote("Changed on another device too. Keep which copy?", alert = true).takeIf { c.syncConflict },
    )
}

private fun itemSubtitle(occ: ScheduleItemOccurrence): String = when {
    occ.continuation -> "Continued from yesterday"
    occ.item.isTask -> "Personal"
    else -> "Work"
}

@Composable
internal fun ItemCard(
    entry: DayEntry.Item,
    conflicts: ItemConflicts,
    d: DayColors,
    timeFormat: TimeFormat,
    onClick: ((ScheduleItem) -> Unit)?,
    onToggleComplete: ((ScheduleItem) -> Unit)?,
    onResolveSyncConflict: ((String) -> Unit)?,
) {
    val item = entry.occurrence.item
    val task = item.isTask
    val done = item.completed == true
    val content = DayCardContent(
        typeWord = if (task) "TASK" else "SHIFT",
        typeColor = (if (task) StatusTone.Purple else StatusTone.Emerald).color(),
        title = item.title,
        subtitle = itemSubtitle(entry.occurrence),
        time = itemTime(entry, timeFormat),
        startColor = d.text,
        struck = task && done,
        clash = conflicts.overlap != null || conflicts.conflicts.any { it.needsAcknowledgment },
        notes = itemNotes(conflicts),
        room = item.location,
        clickLabel = "Open ${item.title}",
    )
    DayCard(
        content,
        d,
        onClick = onClick?.let { { it(item) } },
        trailing = if (task && onToggleComplete != null) {
            { TaskCheck(done, item.title, d.ring, onToggle = { onToggleComplete(item) }) }
        } else null,
        extra = if (conflicts.syncConflict && onResolveSyncConflict != null) {
            { SyncChoice(onResolveSyncConflict) }
        } else null,
    )
}

@Composable
private fun SyncChoice(onResolve: (String) -> Unit) {
    Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VButton("Use server", { onResolve("server") }, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
        VButton("Keep mine", { onResolve("local") }, variant = ButtonVariant.Primary, size = ButtonSize.Sm)
    }
}
