package dev.southstar21.vectra.features.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.ButtonSize
import dev.southstar21.vectra.core.designsystem.components.ButtonVariant
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.StatusLabel
import dev.southstar21.vectra.core.designsystem.components.StatusTone
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.rememberPageClock
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.rgba
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.time.formatTime
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.core.preferences.TimeFormat
import kotlin.time.Instant
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

/** A break between classes is a capsule, not a full-width row. */
private val BreakCapsuleMaxWidth = 384.dp

/** course-tracker AttendanceStatus */
enum class AttendanceStatus { Present, Absent, Excused, Cancelled, Unmarked }

/**
 * The course tracker's touch points on the schedule: attendance on the cards and the
 * Course Hub. The course tracker implements this; the schedule never imports it.
 */
interface ScheduleCourseTrackerHooks {
    /** Status of the session of [event] on [date] (the card's activeSession lookup). */
    @Composable
    fun attendanceStatus(event: ScheduleEvent, date: String): AttendanceStatus

    fun setAttendanceStatus(event: ScheduleEvent, date: String, status: AttendanceStatus)

    /** The key the Course Hub for [event]'s course is pushed with. */
    fun courseKey(event: ScheduleEvent): String
}

/**
 * `#RRGGBB` (or `#RGB`) → opaque Color; [fallback] when unreadable. Unlike the
 * transit `parseHexColor`, an 8-digit value drops its alpha and nothing returns null.
 */
internal fun parseOpaqueHexColor(hex: String?, fallback: Color = Palette.Sky400): Color {
    val h = hex?.trim()?.removePrefix("#") ?: return fallback
    val full = when (h.length) {
        3 -> h.map { "$it$it" }.joinToString("")
        6 -> h
        8 -> h.take(6)
        else -> return fallback
    }
    val v = full.toLongOrNull(16) ?: return fallback
    return Color(0xFF000000 or v)
}

/** The colour at alpha NN/255 (a two-hex-digit alpha suffix). */
internal fun Color.hexAlpha(nn: Int): Color = copy(alpha = nn / 255f)

/** Minute clock: recomposes every minute so "now" states roll over. */
@Composable
internal fun rememberMinuteClock(): Long = rememberPageClock(60_000)

private val CardShape16 = Radius.Panel

/** The glass card surface of a tappable card. */
@Composable
internal fun Modifier.interactiveGlass(shape: Shape = CardShape16): Modifier = glass(shape)

/** The stacked start/end time block on the right of both cards. */
@Composable
private fun StackedTime(start: String, end: String, modifier: Modifier = Modifier, bg: Color? = null, border: Color? = null) {
    val c = Vectra.colors
    val shape = Radius.Panel
    Column(
        modifier
            .boxShadow(shape, listOf(BoxShadow(y = 1f, blur = 2f, color = rgba(0, 0, 0, 0.05f))))
            .background(bg ?: c.surfaceOverlay, shape)
            .border(1.dp, border ?: c.borderSubtle, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        VText(start, style = vtext(TypeScale.Caption, Weight.Bold, Tracking.Tight, lineHeight = 12.sp, mono = true), color = c.textPrimary, maxLines = 1)
        Box(Modifier.padding(vertical = 4.dp).width(14.dp).height(1.dp).alpha(0.6f).background(c.borderPrimary))
        VText(end, style = vtext(TypeScale.Caption, Weight.Bold, Tracking.Tight, lineHeight = 12.sp, mono = true), color = c.textSecondary, maxLines = 1)
    }
}

/** The card's footer bar: a hairline on top over a faint overlay tint. */
@Composable
private fun CardFooterBar(content: @Composable RowScope.() -> Unit) {
    val c = Vectra.colors
    val line = c.borderSubtle.copy(alpha = c.borderSubtle.alpha * 0.6f)
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surfaceOverlay.copy(alpha = c.surfaceOverlay.alpha * 0.2f), RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
            .drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private data class TypeInfo(val label: String, val tone: StatusTone)

/** One class occurrence. */
@Composable
fun ScheduleCard(
    event: ScheduleEvent,
    tracker: ScheduleCourseTrackerHooks,
    modifier: Modifier = Modifier,
    onClick: ((ScheduleEvent) -> Unit)? = null,
    showQuickAttendance: Boolean = true,
    dateStr: String? = null,
    timeFormat: TimeFormat = Vectra.appearance.timeFormat,
    intensiveCourses: Set<String>? = null,
    hasConflict: Boolean = false,
) {
    val c = Vectra.colors
    val clock = rememberMinuteClock()
    val now = nowInBudapest(Instant.fromEpochMilliseconds(clock))
    val currentMinutes = now.hours * 60 + now.minutes
    val activeDate = dateStr ?: now.ymd
    // Without an explicit date the card is "today" only when the class meets today.
    val isToday = if (dateStr != null) dateStr == now.ymd
    else event.dayOfWeek == now.dayOfWeek && classOccursOnDate(event, now.ymd)

    val status = tracker.attendanceStatus(event, activeDate)
    val cancelled = status == AttendanceStatus.Cancelled

    val start = event.startTime.split(':').mapNotNull { it.toIntOrNull() }
    val end = event.endTime.split(':').mapNotNull { it.toIntOrNull() }
    val active = if (start.size >= 2 && end.size >= 2) {
        val s = start[0] * 60 + start[1]
        val e = end[0] * 60 + end[1]
        isToday && currentMinutes >= s && currentMinutes < e
    } else false
    val glowing = active && !cancelled

    val eventColor = parseOpaqueHexColor(event.color.ifEmpty { "#38bdf8" })
    val isIntensive = intensiveCourses?.contains(event.scheduleCourseKey())
        ?: isIntensiveClass(event.courseName, event.courseCode, event.eventType)
    val typeInfo = when {
        event.isExam() -> TypeInfo("Exam", StatusTone.Violet)
        isIntensive -> TypeInfo("Intensive", StatusTone.Amber)
        !event.attendanceType.isNullOrEmpty() -> when (event.attendanceType) {
            "lecture" -> TypeInfo("Lecture", StatusTone.Sky)
            "seminar" -> TypeInfo("Seminar", StatusTone.Emerald)
            "lab" -> TypeInfo("Lab", StatusTone.Purple)
            else -> TypeInfo("Unknown", StatusTone.Neutral)
        }
        event.source == "manual" -> TypeInfo("Unknown", StatusTone.Neutral)
        else -> getClassType(event.courseCode.ifEmpty { event.eventType })?.let { TypeInfo(it.label, it.tone) }
            ?: TypeInfo(event.eventType.ifEmpty { "Lecture" }.replaceFirstChar { it.uppercase() }, StatusTone.Sky)
    }
    val groupCode = extractGroupCode(event.courseCode)
    val canTrack = showQuickAttendance && !event.isExam()

    val shape = CardShape16
    Column(
        modifier
            .fillMaxWidth()
            .alpha(if (cancelled) 0.75f else 1f)
            .then(if (glowing) Modifier.boxShadow(shape, listOf(BoxShadow(blur = 36f, spread = -4f, color = eventColor.hexAlpha(0x45)))) else Modifier)
            .interactiveGlass(shape)
            .then(
                when {
                    glowing -> Modifier
                        .background(Brush.radialGradient(listOf(eventColor.copy(alpha = 0.2f), Color.Transparent)), shape)
                        .border(1.dp, eventColor.hexAlpha(0x70), shape)
                    cancelled -> Modifier
                        .background(c.surfaceSecondary.copy(alpha = 0.4f), shape)
                        .drawBehind {
                            drawRoundRect(
                                c.borderSubtle,
                                cornerRadius = CornerRadius(16.dp.toPx()),
                                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
                            )
                        }
                    else -> Modifier
                },
            )
            .then(if (onClick != null) Modifier.tappable(shape, role = null) { onClick(event) } else Modifier),
    ) {
        Box {
            if (cancelled) {
                VText(
                    "CANCELLED",
                    Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 4.dp).rotate(-6f).alpha(if (c.isDark) 0.08f else 0.05f),
                    vtext(TypeScale.Display, Weight.Black, Tracking.Widest, mono = true),
                    c.textPrimary,
                    maxLines = 1,
                )
            }
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .padding(vertical = 2.dp)
                            .width(6.dp)
                            .fillMaxHeight()
                            .heightIn(min = 36.dp)
                            .alpha(if (cancelled) 0.4f else 1f)
                            .then(if (glowing) Modifier.boxShadow(CircleShape, listOf(BoxShadow(blur = 12f, color = eventColor))) else Modifier)
                            .background(if (cancelled) Palette.Slate500 else eventColor, CircleShape),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusLabel(typeInfo.label, tone = typeInfo.tone)
                            if (hasConflict) StatusLabel("Clash", tone = StatusTone.Red, icon = RemixIcons.AlertLine)
                        }
                        Column {
                            VText(
                                event.courseName,
                                style = vtext(TypeScale.Body, Weight.Bold, lineHeight = 19.25.sp).copy(
                                    textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                                ),
                                color = if (cancelled) c.textMuted else c.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (event.instructor.isNotEmpty()) {
                                VText(event.instructor, Modifier.padding(top = 2.dp), vtext(TypeScale.Caption, Weight.Medium), c.textMuted, maxLines = 1)
                            }
                            if (cancelled) {
                                VText("Cancelled for this date", Modifier.padding(top = 2.dp), vtext(TypeScale.Caption, Weight.Medium), c.tints.red.text)
                            }
                        }
                    }
                }
                if (event.startTime.isNotEmpty() && event.endTime.isNotEmpty()) {
                    StackedTime(
                        formatTime(event.startTime, timeFormat),
                        formatTime(event.endTime, timeFormat),
                        Modifier.alpha(if (cancelled) 0.5f else 1f),
                        bg = if (glowing) eventColor.hexAlpha(0x18) else null,
                        border = if (glowing) eventColor.hexAlpha(0x45) else null,
                    )
                }
            }
        }

        val showCheckIn = canTrack && isToday
        if (event.location.isNotEmpty() || groupCode != null || showCheckIn) {
            CardFooterBar {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (event.location.isNotEmpty()) {
                        VIcon(RemixIcons.MapPinLine, size = 12.dp, tint = c.textSecondary.copy(alpha = 0.8f))
                        VText(event.location, Modifier.weight(1f, fill = false), vtext(TypeScale.Caption, Weight.Semibold), c.textSecondary, maxLines = 1)
                    }
                    if (groupCode != null) {
                        Row(
                            Modifier.padding(start = if (event.location.isNotEmpty()) 6.dp else 0.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            VIcon(RemixIcons.GroupLine, size = 12.dp, tint = c.textSecondary.copy(alpha = 0.8f), contentDescription = "Group")
                            VText(groupCode, style = vtext(TypeScale.Caption, Weight.Semibold), color = c.textSecondary, maxLines = 1)
                        }
                    }
                }
                if (showCheckIn) {
                    val next = when (status) {
                        AttendanceStatus.Unmarked -> AttendanceStatus.Present
                        AttendanceStatus.Present -> AttendanceStatus.Absent
                        AttendanceStatus.Absent -> AttendanceStatus.Excused
                        AttendanceStatus.Excused -> AttendanceStatus.Cancelled
                        AttendanceStatus.Cancelled -> AttendanceStatus.Unmarked
                    }
                    val toaster = LocalToaster.current
                    ChipButton(onClick = {
                        tracker.setAttendanceStatus(event, activeDate, next)
                        val label = when (next) {
                            AttendanceStatus.Present -> "Present (Attended)"
                            AttendanceStatus.Absent -> "Absent (Missed)"
                            AttendanceStatus.Excused -> "Excused"
                            AttendanceStatus.Cancelled -> "Cancelled / Holiday"
                            AttendanceStatus.Unmarked -> "Attendance reset"
                        }
                        toaster.success("${event.courseName}: Marked as $label")
                    }) {
                        val (icon, tint, text) = when (status) {
                            AttendanceStatus.Present -> Triple(RemixIcons.CheckLine, c.tints.emerald.text, "Present")
                            AttendanceStatus.Absent -> Triple(RemixIcons.CloseLine, c.tints.red.text, "Absent")
                            AttendanceStatus.Excused -> Triple(RemixIcons.ErrorWarningLine, c.tints.amber.text, "Excused")
                            AttendanceStatus.Cancelled -> Triple(RemixIcons.CalendarCloseLine, c.tints.red.text, "Holiday")
                            AttendanceStatus.Unmarked -> Triple(RemixIcons.RadioButtonLine, c.textMuted, "Check in")
                        }
                        VIcon(icon, size = 14.dp, tint = tint)
                        VText(text, style = vtext(TypeScale.Caption, Weight.Semibold), color = c.textSecondary, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Shift and task accents mirror the class card's per-course colour bar. */
private val ShiftAccent = Palette.Emerald500
private val TaskAccent = Palette.Violet500

/** Midnight is stored as 24:00 for range math, but a clock reads 00:00. */
private fun itemClock(time: String, format: TimeFormat) = formatTime(if (time == "24:00") "00:00" else time, format)

/** One shift or task occurrence. */
@Composable
fun ScheduleItemCard(
    occurrence: ScheduleItemOccurrence,
    modifier: Modifier = Modifier,
    conflicts: List<ScheduleConflict> = emptyList(),
    onClick: ((ScheduleItem) -> Unit)? = null,
    onToggleComplete: ((ScheduleItem) -> Unit)? = null,
    timeFormat: TimeFormat = TimeFormat.H24,
    syncConflict: Boolean = false,
    isWaitingForAcknowledgment: Boolean = false,
    onResolveSyncConflict: ((String) -> Unit)? = null,
) {
    val c = Vectra.colors
    val item = occurrence.item
    val isTask = item.isTask
    val pending = conflicts.any { it.needsAcknowledgment }
    val acknowledged = conflicts.isNotEmpty() && !pending
    val accent = if (isTask) TaskAccent else ShiftAccent
    val done = item.completed == true
    val shape = CardShape16
    val stateBorder = when {
        pending -> c.tints.red.border
        syncConflict -> c.tints.purple.border
        acknowledged -> c.tints.amber.border
        else -> null
    }
    Column(
        modifier
            .fillMaxWidth()
            .alpha(if (done) 0.7f else 1f)
            .then(if (pending) Modifier.boxShadow(shape, listOf(BoxShadow(blur = 32f, spread = -6f, color = rgba(239, 68, 68, 0.35f)))) else Modifier)
            .interactiveGlass(shape)
            .then(if (stateBorder != null) Modifier.border(1.dp, stateBorder, shape) else Modifier),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .tappable(shape, role = null) { onClick?.invoke(item) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(vertical = 2.dp).width(6.dp).fillMaxHeight().heightIn(min = 36.dp).background(accent, CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusLabel(if (isTask) "Task" else "Shift", tone = if (isTask) StatusTone.Purple else StatusTone.Emerald)
                        if (syncConflict) StatusLabel("Sync conflict", tone = StatusTone.Purple, icon = RemixIcons.RefreshLine)
                        if (conflicts.isNotEmpty()) {
                            StatusLabel(if (pending) "Clash" else "Acknowledged", tone = if (pending) StatusTone.Red else StatusTone.Amber, icon = RemixIcons.AlertLine)
                        }
                        if (occurrence.continuation) {
                            VText(
                                "OVERNIGHT",
                                Modifier.background(c.surfaceOverlay, CircleShape).border(1.dp, c.borderSubtle, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
                                vtext(TypeScale.Micro, Weight.Bold, Tracking.Wider),
                                c.textSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                    Column {
                        VText(
                            item.title,
                            style = vtext(TypeScale.Body, Weight.Bold, lineHeight = 19.25.sp).copy(textDecoration = if (done) TextDecoration.LineThrough else null),
                            color = if (done) c.textMuted else c.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (occurrence.continuation) {
                            VText("Continues from the previous day", Modifier.padding(top = 2.dp), vtext(TypeScale.Caption, Weight.Medium), c.textMuted, maxLines = 1)
                        }
                        if (pending || isWaitingForAcknowledgment) {
                            VText("Open this item to review and acknowledge the clash", Modifier.padding(top = 2.dp), vtext(TypeScale.Caption, Weight.Medium), c.tints.red.text)
                        }
                    }
                }
            }
            StackedTime(itemClock(occurrence.startTime, timeFormat), itemClock(occurrence.endTime, timeFormat))
        }

        val showFooter = !item.location.isNullOrEmpty() || syncConflict || (isTask && onToggleComplete != null)
        if (showFooter) {
            CardFooterBar {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!item.location.isNullOrEmpty()) {
                        VIcon(RemixIcons.MapPinLine, size = 12.dp, tint = c.textSecondary.copy(alpha = 0.8f))
                        VText(item.location, style = vtext(TypeScale.Caption, Weight.Semibold, mono = true), color = c.textSecondary, maxLines = 1)
                    }
                }
                if (syncConflict && onResolveSyncConflict != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        VButton("Use server", { onResolveSyncConflict("server") }, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
                        VButton("Keep mine", { onResolveSyncConflict("local") }, variant = ButtonVariant.Primary, size = ButtonSize.Sm)
                    }
                } else if (isTask && onToggleComplete != null) {
                    ChipButton(onClick = { onToggleComplete(item) }) {
                        if (done) VIcon(RemixIcons.CheckLine, size = 14.dp, tint = c.tints.emerald.text)
                        else VIcon(RemixIcons.RadioButtonLine, size = 14.dp, tint = c.textMuted)
                        VText(if (done) "Done" else "Mark done", style = vtext(TypeScale.Caption, Weight.Semibold), color = c.textSecondary, maxLines = 1)
                    }
                }
            }
        }
    }
}

private sealed interface TimelineEntry {
    val startTime: String
    val endTime: String
    val kind: String

    data class Class(val event: ScheduleEvent) : TimelineEntry {
        override val startTime get() = event.startTime
        override val endTime get() = event.endTime
        override val kind get() = "class"
    }

    data class Item(val occurrence: ScheduleItemOccurrence) : TimelineEntry {
        override val startTime get() = occurrence.startTime
        override val endTime get() = occurrence.endTime
        override val kind get() = "item"
    }
}

/**
 * A day's classes and personal items in one
 * chronological column, with break capsules measured across both kinds.
 * Shared with the dashboard for visual and behavioural parity.
 */
@Composable
fun ScheduleDayTimeline(
    events: List<ScheduleEvent>,
    tracker: ScheduleCourseTrackerHooks,
    modifier: Modifier = Modifier,
    items: List<ScheduleItemOccurrence> = emptyList(),
    dateStr: String? = null,
    onEventClick: ((ScheduleEvent) -> Unit)? = null,
    onItemClick: ((ScheduleItem) -> Unit)? = null,
    onToggleTaskCompleted: ((ScheduleItem) -> Unit)? = null,
    onResolveSyncConflict: ((id: String, resolution: String) -> Unit)? = null,
    syncConflictIds: Set<String> = emptySet(),
    needsAcknowledgmentIds: Set<String> = emptySet(),
    classConflictsByEventId: Map<String, List<ScheduleConflict>> = emptyMap(),
    itemConflictsById: Map<String, List<ScheduleConflict>> = emptyMap(),
    showQuickAttendance: Boolean = true,
    timeFormat: TimeFormat = Vectra.appearance.timeFormat,
    intensiveCourses: Set<String>? = null,
    emptyPlaceholder: (@Composable () -> Unit)? = null,
) {
    val c = Vectra.colors
    val entries = remember(events, items) {
        (events.map { TimelineEntry.Class(it) } + items.map { TimelineEntry.Item(it) })
            .sortedWith(compareBy<TimelineEntry> { it.startTime }.thenBy { it.kind })
    }
    if (entries.isEmpty()) {
        emptyPlaceholder?.invoke()
        return
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        entries.forEachIndexed { index, entry ->
            val next = entries.getOrNull(index + 1)
            val breakInfo = next?.let { calculateBreakInfo(entry.endTime, it.startTime) }
            when (entry) {
                is TimelineEntry.Class -> ScheduleCard(
                    event = entry.event,
                    tracker = tracker,
                    onClick = onEventClick,
                    showQuickAttendance = showQuickAttendance && !entry.event.isExam(),
                    dateStr = dateStr,
                    timeFormat = timeFormat,
                    intensiveCourses = intensiveCourses,
                    hasConflict = (if (dateStr != null) classConflictsByEventId["$dateStr:${entry.event.id}"] else classConflictsByEventId[entry.event.id])
                        .orEmpty().isNotEmpty(),
                )
                is TimelineEntry.Item -> {
                    val id = entry.occurrence.item.id
                    ScheduleItemCard(
                        occurrence = entry.occurrence,
                        conflicts = itemConflictsById["${entry.occurrence.date}:$id"].orEmpty(),
                        onClick = onItemClick,
                        onToggleComplete = onToggleTaskCompleted,
                        timeFormat = timeFormat,
                        syncConflict = id in syncConflictIds,
                        isWaitingForAcknowledgment = id in needsAcknowledgmentIds,
                        onResolveSyncConflict = onResolveSyncConflict?.let { cb -> { r: String -> cb(id, r) } },
                    )
                }
            }
            if (breakInfo != null) {
                val amberLine = Palette.Amber500.copy(alpha = 0.6f)
                Box(Modifier.width(2.dp).height(14.dp).background(amberLine))
                BreakCapsule(breakInfo, timeFormat)
                Box(Modifier.width(2.dp).height(14.dp).background(amberLine))
            } else if (next != null) {
                Box(Modifier.width(2.dp).height(16.dp).background(c.borderPrimary))
            }
        }
    }
}

@Composable
private fun BreakCapsule(info: BreakInfo, timeFormat: TimeFormat) {
    val c = Vectra.colors
    Row(
        Modifier
            .widthIn(max = BreakCapsuleMaxWidth)
            .fillMaxWidth()
            .glass(CircleShape)
            .background(c.surfaceSecondary, CircleShape)
            .border(1.dp, c.borderSubtle, CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.widthIn(min = 64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            VIcon(RemixIcons.CupLine, size = 16.dp, tint = Palette.Amber700)
            VText("Break", style = vtext(TypeScale.Caption, Weight.Bold, mono = true), color = c.textPrimary, maxLines = 1)
        }
        VText(
            "${formatTime(info.startTime, timeFormat)} – ${formatTime(info.endTime, timeFormat)}",
            Modifier.weight(1f),
            vtext(TypeScale.Caption, Weight.Medium, mono = true),
            c.textSecondary,
            align = TextAlign.Center,
            maxLines = 1,
        )
        Box(Modifier.widthIn(min = 64.dp), contentAlignment = Alignment.CenterEnd) {
            StatusLabel(info.durationLabel, tone = StatusTone.Amber)
        }
    }
}
