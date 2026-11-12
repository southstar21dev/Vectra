package dev.southstar21.vectra.features.schedule

import androidx.compose.animation.animateContentSize
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.roundToInt
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.ButtonSize
import dev.southstar21.vectra.core.designsystem.components.ButtonVariant
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.components.color
import dev.southstar21.vectra.core.designsystem.components.StatusTone
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraSprings
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.rgba
import dev.southstar21.vectra.core.designsystem.theme.specularBorder
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.preferences.TimeFormat
import dev.southstar21.vectra.core.time.formatDayMonth
import dev.southstar21.vectra.core.time.formatMinuteOfDay
import dev.southstar21.vectra.core.time.daysSinceSunday
import dev.southstar21.vectra.core.time.formatTime
import dev.southstar21.vectra.core.time.getDayName
import dev.southstar21.vectra.core.time.parseYmd
import dev.southstar21.vectra.core.time.ymd
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import dev.southstar21.vectra.core.time.nowInBudapest
import kotlinx.coroutines.delay
import kotlin.time.Instant

// ---- Tokens ------------------------------------------------------------------------

/** The day list's colours (builder spec 1, "Colour tokens"). */
@Immutable
internal class DayColors(val isDark: Boolean) {
    val card = if (isDark) Color(0xFF0E0F14) else Color.White
    /** A cancelled class: darker than a card, still flat (never carved in). */
    val cancelledCard = if (isDark) Color(0xFF08090B) else Color(0xFFE2E8F0)
    val strip = if (isDark) Color(0xFF14151C) else Color(0xFFF8FAFC)
    val border = if (isDark) rgba(255, 255, 255, 0.08f) else rgba(15, 23, 42, 0.08f)
    val borderTop = if (isDark) rgba(255, 255, 255, 0.2f) else rgba(15, 23, 42, 0.1f)
    val cardShadow = listOf(BoxShadow(y = 16f, blur = 36f, spread = -8f, color = if (isDark) rgba(0, 0, 0, 0.5f) else rgba(15, 23, 42, 0.12f)))
    val stripShadow = listOf(BoxShadow(y = 8f, blur = 18f, spread = -6f, color = if (isDark) rgba(0, 0, 0, 0.75f) else rgba(15, 23, 42, 0.1f)))
    val smallShadow = listOf(BoxShadow(y = 10f, blur = 22f, spread = -10f, color = if (isDark) rgba(0, 0, 0, 0.8f) else rgba(15, 23, 42, 0.14f)))
    val text = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val secondary = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569)
    val muted = if (isDark) Color(0xFF7C8AA0) else Color(0xFF64748B)
    val room = if (isDark) Color(0xFFCBD5E1) else Color(0xFF334155)
    val rule = if (isDark) rgba(255, 255, 255, 0.18f) else rgba(15, 23, 42, 0.18f)
    val line = if (isDark) rgba(255, 255, 255, 0.16f) else rgba(15, 23, 42, 0.14f)
    val ring = if (isDark) rgba(255, 255, 255, 0.25f) else rgba(15, 23, 42, 0.25f)
    val nowFill = if (isDark) rgba(255, 255, 255, 0.06f) else rgba(15, 23, 42, 0.05f)
    val nowEdge = if (isDark) rgba(255, 255, 255, 0.12f) else rgba(15, 23, 42, 0.12f)
    val nowBorder = if (isDark) rgba(255, 255, 255, 0.14f) else rgba(15, 23, 42, 0.14f)
    val edgeNear = if (isDark) Color(0xFF13141B) else Color(0xFFE9EDF3)
    val edgeNearBorder = if (isDark) rgba(255, 255, 255, 0.11f) else rgba(15, 23, 42, 0.12f)
    val edgeFar = if (isDark) Color(0xFF101117) else Color(0xFFE2E7EE)
    val edgeFarBorder = if (isDark) rgba(255, 255, 255, 0.09f) else rgba(15, 23, 42, 0.1f)
    val placeholder = if (isDark) rgba(255, 255, 255, 0.07f) else rgba(15, 23, 42, 0.07f)
    val placeholderStrip = if (isDark) Color(0xFF121318) else Color(0xFFE9EDF3)
    val clashBorder = rgba(248, 113, 113, 0.45f)
    val clashIcon = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)
    val clashText = if (isDark) Color(0xFFFCA5A5) else Color(0xFFB91C1C)
    val dashed = rgba(148, 163, 184, 0.6f)
    fun timeLeft(tone: TimeLeftTone): Color = when (tone) {
        TimeLeftTone.Plenty -> if (isDark) Color(0xFF34D399) else Color(0xFF059669)
        TimeLeftTone.Soon -> if (isDark) Color(0xFFFBBF24) else Color(0xFFB45309)
        TimeLeftTone.Urgent -> if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)
    }
}

@Composable
internal fun rememberDayColors(): DayColors {
    val dark = Vectra.colors.isDark
    return remember(dark) { DayColors(dark) }
}

private val CardShape = RoundedCornerShape(22.dp)
private val StripShape = RoundedCornerShape(18.dp)
private val SmallShape = RoundedCornerShape(16.dp)
private val EdgeShape = RoundedCornerShape(15.dp)

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/** The course colour, stepped toward white (dark) or black (light) until it reads on [bg]. */
internal fun readableOn(color: Color, bg: Color, dark: Boolean): Color {
    var out = color
    var step = 0
    while (contrast(out, bg) < 4.5f && step < 10) {
        step++
        out = lerp(color, if (dark) Color.White else Color.Black, step / 10f)
    }
    return out
}

// ---- Shared pieces -------------------------------------------------------------------

/** The type word above a card's title: LECTURE, LAB, SEMINAR, EXAM, INTENSIVE, SHIFT, TASK. */
@Composable
private fun TypeWord(word: String, color: Color) {
    VText(word, style = vtext(12.sp to 14.sp, Weight.Extrabold, 0.16.em, mono = true), color = color, maxLines = 1)
}

/** What the stacked time block on the right shows. */
private sealed interface TimeBlock {
    data class Range(val start: String, val end: String, val nextDay: Boolean = false) : TimeBlock
    data class Due(val time: String) : TimeBlock
    data object AllDay : TimeBlock
}

@Composable
private fun StackedTimeBlock(block: TimeBlock, startColor: Color, d: DayColors, struck: Boolean) {
    val style = vtext(15.sp to 18.sp, Weight.Bold, mono = true).tnum()
        .copy(textDecoration = if (struck) TextDecoration.LineThrough else null)
    Column(
        Modifier.padding(start = 8.dp, top = 2.dp, end = 2.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (block) {
            is TimeBlock.Range -> {
                VText(block.start, style = style, color = startColor, maxLines = 1)
                Box(Modifier.padding(vertical = 6.dp).size(16.dp, 1.5.dp).background(d.rule, RoundedCornerShape(1.dp)))
                Row(verticalAlignment = Alignment.Top) {
                    VText(block.end, style = style, color = d.secondary, maxLines = 1)
                    if (block.nextDay) {
                        VText("+1", Modifier.padding(start = 2.dp), vtext(10.sp to 12.sp, Weight.Bold, mono = true), d.muted, maxLines = 1)
                    }
                }
            }
            is TimeBlock.Due -> {
                VText("DUE", style = vtext(11.sp to 14.sp, Weight.Extrabold, 0.12.em, mono = true), color = d.muted, maxLines = 1)
                VText(block.time, Modifier.padding(top = 4.dp), style, startColor, maxLines = 1)
            }
            TimeBlock.AllDay -> {
                VText("ALL", style = vtext(12.sp to 15.sp, Weight.Extrabold, 0.12.em, mono = true), color = d.secondary, maxLines = 1)
                VText("DAY", style = vtext(12.sp to 15.sp, Weight.Extrabold, 0.12.em, mono = true), color = d.secondary, maxLines = 1)
            }
        }
    }
}

/** A line under the title row: a clash, a cancellation, an offline check-in. */
private data class CardNote(val text: String, val alert: Boolean = false)

/**
 * The card every class and item shares: type word, title, subtitle and the stacked time,
 * then the room row with the check-in at the far right.
 */
@Composable
private fun DayCard(
    d: DayColors,
    typeWord: String,
    typeColor: Color,
    title: String,
    subtitle: String?,
    time: TimeBlock,
    startColor: Color,
    struck: Boolean,
    faded: Boolean,
    clash: Boolean,
    notes: List<CardNote>,
    room: String?,
    group: String?,
    onClick: (() -> Unit)?,
    clickLabel: String,
    trailing: (@Composable () -> Unit)?,
    extra: (@Composable () -> Unit)? = null,
    /** A rubber stamp across the card ("CANCELLED"); it stays at full strength over the faded contents. */
    stamp: String? = null,
) {
    val hasRoomRow = !room.isNullOrEmpty() || group != null || trailing != null
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .boxShadow(CardShape, d.cardShadow)
                .background(if (faded) d.cancelledCard else d.card, CardShape)
                .then(if (clash) Modifier.specularBorder(CardShape, d.clashBorder, d.clashBorder) else Modifier.specularBorder(CardShape, d.border, d.borderTop))
                .then(if (onClick != null) Modifier.tappable(CardShape, role = null, onClickLabel = clickLabel, onClick = onClick) else Modifier)
                // A cancelled card fades its contents only: the card itself stays opaque, so the
                // connector line behind the list never shows through it.
                .alpha(if (faded) 0.45f else 1f)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (hasRoomRow) 10.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    TypeWord(typeWord, typeColor)
                    VText(
                        title,
                        style = vtext(17.sp to 22.sp, Weight.Bold).copy(textDecoration = if (struck) TextDecoration.LineThrough else null),
                        color = d.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrEmpty()) {
                        VText(subtitle, style = vtext(14.sp to 19.sp, Weight.Medium), color = d.secondary, maxLines = 1)
                    }
                }
                StackedTimeBlock(time, startColor, d, struck)
            }
            for (note in notes) {
                Row(Modifier.padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (note.alert) VIcon(RemixIcons.AlertLine, size = 14.dp, tint = d.clashIcon)
                    VText(note.text, style = vtext(12.sp to 16.sp, Weight.Semibold).tnum(), color = if (note.alert) d.clashText else d.secondary)
                }
            }
            if (hasRoomRow) {
                Row(Modifier.heightIn(min = 44.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!room.isNullOrEmpty()) {
                            VIcon(RemixIcons.MapPinLine, size = 17.dp, tint = d.secondary)
                            // The room is the only thing in the row that shortens.
                            VText(room, Modifier.weight(1f, fill = false), vtext(14.sp to 19.sp, Weight.Semibold), d.room, maxLines = 1)
                        }
                        if (group != null) {
                            Row(
                                Modifier.padding(start = if (room.isNullOrEmpty()) 0.dp else 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                VIcon(RemixIcons.GroupLine, size = 17.dp, tint = d.secondary, contentDescription = "Group")
                                VText(group, style = vtext(14.sp to 19.sp, Weight.Semibold), color = d.room, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                            }
                        }
                    }
                    // The 44dp slot overhangs the card's padding by 6dp, like the mockup's -6px margin.
                    if (trailing != null) Box(Modifier.offset(x = 6.dp)) { trailing() }
                }
            }
            extra?.invoke()
        }
        if (stamp != null) CardStamp(stamp, d, Modifier.align(Alignment.Center))
    }
}

/**
 * A tilted rubber stamp, drawn over the card and never taking taps. It is a raised object: a
 * drop shadow under it, a red-tinted face lit from the top, a brighter top edge and a soft
 * shadow under the letters.
 */
@Composable
private fun CardStamp(text: String, d: DayColors, modifier: Modifier = Modifier) {
    val ink = if (d.isDark) Color(0xFFF87171) else Color(0xFFDC2626)
    val edgeLight = if (d.isDark) Color(0xFFFECACA) else Color(0xFFF87171)
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier
            .rotate(-12f)
            .boxShadow(
                shape,
                listOf(
                    BoxShadow(y = 8f, blur = 18f, spread = -2f, color = Color.Black.copy(alpha = if (d.isDark) 0.75f else 0.28f)),
                    BoxShadow(y = 2f, blur = 4f, color = Color.Black.copy(alpha = if (d.isDark) 0.5f else 0.18f)),
                ),
            )
            .background(
                Brush.verticalGradient(listOf(lerp(d.cancelledCard, ink, 0.30f), lerp(d.cancelledCard, ink, 0.12f))),
                shape,
            )
            .specularBorder(shape, ink.copy(alpha = 0.9f), edgeLight, width = 2.5f)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        VText(
            text,
            style = vtext(20.sp to 26.sp, Weight.Extrabold, 0.18.em, mono = true).copy(
                shadow = Shadow(Color.Black.copy(alpha = if (d.isDark) 0.6f else 0.25f), Offset(0f, 2f), 4f),
            ),
            color = ink,
            maxLines = 1,
        )
    }
}

// ---- Class and item cards -----------------------------------------------------------

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

@Composable
private fun ClassCard(
    entry: DayEntry.Class,
    status: AttendanceStatus,
    d: DayColors,
    focus: Boolean,
    nowMinute: Int?,
    overlap: Overlap?,
    repoClash: Boolean,
    canCheckIn: Boolean,
    pendingSync: Boolean,
    timeFormat: TimeFormat,
    intensiveCourses: Set<String>?,
    onClick: ((ScheduleEvent) -> Unit)?,
    streak: () -> Int,
    onSetStatus: (AttendanceStatus) -> Unit,
) {
    val event = entry.event
    val raw = parseOpaqueHexColor(event.color.ifEmpty { "#38bdf8" })
    val course = readableOn(raw, d.card, d.isDark)
    val startTint = if (focus) {
        if (d.isDark) lerp(raw, Color.White, 0.65f) else lerp(course, Color.Black, 0.15f)
    } else d.text
    val live = focus && nowMinute != null && nowMinute >= entry.start && nowMinute < entry.end
    val type = classTypeWord(event, intensiveCourses)
    val notes = buildList {
        overlap?.let { add(overlapNote(it)) }
        // No "cancelled" line: it would push the orb down between taps of the status cycle.
        // The struck-through title, faded card and grey calendar orb already say it.
        if (pendingSync && status != AttendanceStatus.Unmarked) add(CardNote("Checked in. Will sync when you are back online"))
    }
    DayCard(
        d = d,
        typeWord = type.first,
        typeColor = type.second.color(),
        title = event.courseName,
        subtitle = event.instructor,
        time = TimeBlock.Range(formatTime(event.startTime, timeFormat), formatTime(event.endTime, timeFormat)),
        startColor = startTint,
        struck = false,
        faded = entry.cancelled,
        stamp = if (entry.cancelled) "CANCELLED" else null,
        clash = overlap != null || repoClash,
        notes = notes,
        room = event.location,
        group = extractGroupCode(event.courseCode),
        onClick = onClick?.let { { it(event) } },
        clickLabel = "Open ${event.courseName}",
        trailing = if (!canCheckIn) null else {
            {
                CheckInOrb(
                    status = status,
                    title = event.courseName,
                    courseColor = course,
                    focus = focus,
                    live = live,
                    pendingSync = pendingSync,
                    neutralRing = d.ring,
                    badgeBg = d.card,
                    streak = streak,
                    onSetStatus = onSetStatus,
                )
            }
        },
    )
}

@Composable
private fun ItemCard(
    entry: DayEntry.Item,
    d: DayColors,
    overlap: Overlap?,
    conflicts: List<ScheduleConflict>,
    syncConflict: Boolean,
    waitingForAck: Boolean,
    timeFormat: TimeFormat,
    onClick: ((ScheduleItem) -> Unit)?,
    onToggleComplete: ((ScheduleItem) -> Unit)?,
    onResolveSyncConflict: ((String) -> Unit)?,
) {
    val occ = entry.occurrence
    val item = occ.item
    val task = item.isTask
    val done = item.completed == true
    val pending = conflicts.any { it.needsAcknowledgment }
    // An overnight shift's first evening ends at midnight here; show its real end with "+1".
    val realEnd = minuteOf(item.endTime)
    val nextDay = !occ.continuation && entry.end == 24 * 60 && realEnd != null && realEnd < entry.start
    fun clock(t: String) = formatTime(if (t == "24:00") "00:00" else t, timeFormat)
    val time = when {
        task && entry.start == 0 && entry.end >= 24 * 60 -> TimeBlock.AllDay
        task -> TimeBlock.Due(clock(occ.endTime))
        nextDay -> TimeBlock.Range(clock(occ.startTime), clock(item.endTime), nextDay = true)
        else -> TimeBlock.Range(clock(occ.startTime), clock(occ.endTime))
    }
    val repoOverlap = conflicts.firstOrNull()?.let { Overlap(it.otherTitle, it.startMinute, it.endMinute) }
    val notes = buildList {
        (overlap ?: repoOverlap?.takeIf { pending })?.let { add(overlapNote(it)) }
        if (pending || waitingForAck) add(CardNote("Open this item to review and acknowledge the clash", alert = true))
        if (syncConflict) add(CardNote("Changed on another device too. Keep which copy?", alert = true))
    }
    DayCard(
        d = d,
        typeWord = if (task) "TASK" else "SHIFT",
        typeColor = (if (task) StatusTone.Purple else StatusTone.Emerald).color(),
        title = item.title,
        subtitle = when {
            occ.continuation -> "Continued from yesterday"
            task -> "Personal"
            else -> "Work"
        },
        time = time,
        startColor = d.text,
        struck = task && done,
        faded = false,
        clash = overlap != null || pending,
        notes = notes,
        room = item.location,
        group = null,
        onClick = onClick?.let { { it(item) } },
        clickLabel = "Open ${item.title}",
        trailing = if (task && onToggleComplete != null) {
            { TaskCheck(done, item.title, d.ring, onToggle = { onToggleComplete(item) }) }
        } else null,
        extra = if (syncConflict && onResolveSyncConflict != null) {
            {
                Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VButton("Use server", { onResolveSyncConflict("server") }, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
                    VButton("Keep mine", { onResolveSyncConflict("local") }, variant = ButtonVariant.Primary, size = ButtonSize.Sm)
                }
            }
        } else null,
    )
}

// ---- Break strip -----------------------------------------------------------------------

@Composable
private fun BreakStrip(row: DayRow.Break, nowMinute: Int?, d: DayColors, timeFormat: TimeFormat) {
    val accent = Vectra.colors.accentPrimary
    val now = row.state == BreakState.Now && nowMinute != null
    val left = if (now) row.end - nowMinute else 0
    val fraction = if (now) ((nowMinute - row.start).toFloat() / row.minutes).coerceIn(0f, 1f) else 0f
    val (rightText, rightColor) = when (row.state) {
        BreakState.Now -> "${durationLabel(left)} left" to d.timeLeft(timeLeftTone(left))
        BreakState.Next -> durationLabel(row.minutes) to d.text
        BreakState.Later -> durationLabel(row.minutes) to d.secondary
    }
    val range = "${formatTime(formatMinuteOfDay(row.start), timeFormat)} – ${formatTime(formatMinuteOfDay(row.end % (24 * 60)), timeFormat)}"
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .boxShadow(StripShape, d.stripShadow)
            .background(d.strip, StripShape)
            .specularBorder(StripShape, if (now) d.nowBorder else d.border, if (now) d.nowBorder else d.border)
            .clip(StripShape)
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.CenterStart,
    ) {
        if (now && fraction > 0f) {
            // The elapsed part of the break: a neutral fill growing from the left.
            Box(
                Modifier.matchParentSize().drawBehind {
                    val w = size.width * fraction
                    drawRect(d.nowFill, size = Size(w, size.height))
                    drawLine(d.nowEdge, Offset(w, 0f), Offset(w, size.height), 1.dp.toPx())
                },
            )
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VText(if (now) "Break now" else "Break", Modifier.weight(1f), vtext(13.sp to 18.sp, Weight.Bold), accent, maxLines = 1)
            VText(range, style = vtext(12.sp to 16.sp, Weight.Bold, mono = true).tnum(), color = d.room, maxLines = 1)
            VText(rightText, Modifier.weight(1f), vtext(13.sp to 18.sp, Weight.Semibold), rightColor, align = TextAlign.End, maxLines = 1)
        }
    }
}

// ---- Finished stack -----------------------------------------------------------------------

private const val StackCardDp = 50
private const val StackGapDp = 6
private const val StackPeekDp = 7

private fun mix(a: Float, b: Float, f: Float) = a + (b - a) * f

@Composable
private fun SmallCard(
    entry: DayEntry,
    status: AttendanceStatus?,
    subtitle: String?,
    d: DayColors,
    timeFormat: TimeFormat,
    onTap: () -> Unit,
    tapLabel: String,
    onDisc: (() -> Unit)?,
    modifier: Modifier = Modifier,
    bg: Color = d.card,
    contentAlpha: Float = 1f,
    subtitleAlpha: Float = 1f,
    enabled: Boolean = true,
) {
    val square = entry is DayEntry.Item && entry.occurrence.item.isTask
    val range = "${formatTime(formatMinuteOfDay(entry.start), timeFormat)} – ${formatTime(formatMinuteOfDay(entry.end % (24 * 60)), timeFormat)}"
    Row(
        modifier
            .fillMaxWidth()
            .height(StackCardDp.dp)
            .boxShadow(SmallShape, d.smallShadow)
            .background(bg, SmallShape)
            .specularBorder(SmallShape, d.border, if (d.isDark) rgba(255, 255, 255, 0.18f) else rgba(15, 23, 42, 0.18f))
            .tappable(SmallShape, enabled = enabled, role = Role.Button, onClickLabel = tapLabel, onClick = onTap)
            .padding(start = 13.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fade = Modifier.graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) }
        SmallStatusDisc(
            status,
            d.dashed,
            square = square,
            modifier = fade.then(
                if (onDisc != null) Modifier.tappable(RoundedCornerShape(50), enabled = enabled, role = Role.Button, onClickLabel = "Change attendance for ${entry.title}", onClick = onDisc)
                else Modifier,
            ),
        )
        Column(fade.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            VText(entry.title, style = vtext(14.sp to 17.sp, Weight.Bold), color = d.room, maxLines = 1)
            if (subtitle != null && subtitleAlpha > 0.01f) {
                VText(subtitle, Modifier.graphicsLayer { alpha = subtitleAlpha.coerceIn(0f, 1f) }, vtext(11.sp to 13.sp, Weight.Semibold), d.muted, maxLines = 1)
            }
        }
        VText(range, fade, vtext(12.sp to 16.sp, Weight.Bold, mono = true).tnum(), d.muted, maxLines = 1)
    }
}

/**
 * Finished classes as a stack. Every card stays composed and one spring ([open] 0 → 1) moves
 * each of them between its stacked spot and its place in the list, so fanning out and folding
 * are the same continuous motion, played forwards or backwards (and can reverse mid-way).
 * Folded, the newest sits in front and up to two older cards peek out below it, narrower.
 */
@Composable
private fun FinishedStack(
    finished: List<DayEntry>,
    open: Boolean,
    onToggle: () -> Unit,
    d: DayColors,
    timeFormat: TimeFormat,
    statusOf: (DayEntry) -> AttendanceStatus?,
    onDisc: (DayEntry) -> (() -> Unit)?,
) {
    val reduceMotion = Vectra.reduceMotion
    val n = finished.size
    val fan by animateFloatAsState(
        if (open) 1f else 0f,
        if (reduceMotion) tween(150) else spring(dampingRatio = 0.86f, stiffness = 300f),
        label = "stackFan",
    )
    val label = "$n finished ${if (n == 1) "item" else "items"}. ${if (open) "Tap to stack them" else "Tap to show them"}"
    val edges = (n - 1).coerceAtMost(2)
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            finished.forEachIndexed { i, e ->
                key(e.key) {
                    // 0 is the newest card, in front; 1 and 2 are the edges peeking out below it.
                    val depth = n - 1 - i
                    val folded = depth.coerceAtMost(2)
                    val edgeBg = when (folded) {
                        0 -> d.card
                        1 -> d.edgeNear
                        else -> d.edgeFar
                    }
                    SmallCard(
                        e,
                        statusOf(e),
                        if (depth == 0 && n > 1) "and ${n - 1} earlier" else null,
                        d,
                        timeFormat,
                        onTap = onToggle,
                        tapLabel = label,
                        onDisc = onDisc(e),
                        bg = lerp(edgeBg, d.card, fan.coerceIn(0f, 1f)),
                        contentAlpha = if (depth == 0) 1f else fan * 1.4f - 0.4f,
                        subtitleAlpha = 1f - fan * 2f,
                        // Folded, only the front card answers; the ones behind it are out of reach.
                        enabled = depth == 0 || open,
                        modifier = Modifier
                            .then(if (depth > 0 && !open) Modifier.clearAndSetSemantics { } else Modifier)
                            .graphicsLayer {
                                val inset = 11.dp.toPx() * folded
                                scaleX = mix(1f - 2f * inset / size.width, 1f, fan)
                                // Cards deeper than the two edges hide exactly behind the second one.
                                if (depth > 2) alpha = fan.coerceIn(0f, 1f)
                            },
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val cardH = StackCardDp.dp.toPx()
        val step = cardH + StackGapDp.dp.toPx()
        val peek = StackPeekDp.dp.toPx()
        val placeables = measurables.map { it.measure(Constraints.fixed(w, cardH.roundToInt())) }
        val foldedH = cardH + peek * edges
        val openH = n * cardH + (n - 1) * StackGapDp.dp.toPx()
        layout(w, mix(foldedH, openH, fan).roundToInt().coerceAtLeast(cardH.roundToInt())) {
            placeables.forEachIndexed { i, p ->
                val depth = n - 1 - i
                val foldedY = peek * depth.coerceAtMost(2)
                // Open, the list reads oldest to newest, top to bottom.
                p.place(0, mix(foldedY, i * step, fan).roundToInt(), zIndex = -depth.toFloat())
            }
        }
    }
}

// ---- Closing, empty and loading states ----------------------------------------------------

@Composable
private fun DayDoneCard(summary: String, d: DayColors) {
    Row(
        Modifier
            .fillMaxWidth()
            .boxShadow(CardShape, d.cardShadow)
            .background(d.card, CardShape)
            .specularBorder(CardShape, d.border, d.borderTop)
            .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DoneDisc()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            VText("All done for today", style = vtext(18.sp to 24.sp, Weight.Extrabold, (-0.01).em), color = d.text)
            VText(summary, style = vtext(13.sp to 18.sp, Weight.Medium), color = d.secondary)
        }
    }
}

/** "Nothing scheduled" with when things start again ([backOn] is e.g. "Monday" or "tomorrow"). */
@Composable
fun NothingScheduledCard(backOn: String?, backAt: String?, modifier: Modifier = Modifier) {
    val d = rememberDayColors()
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .boxShadow(shape, d.cardShadow)
            .background(d.card, shape)
            .specularBorder(shape, d.border, d.borderTop)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        VText("Nothing scheduled", style = vtext(20.sp to 26.sp, Weight.Extrabold, (-0.01).em), color = d.text, align = TextAlign.Center)
        if (backOn != null && backAt != null) {
            val text = buildAnnotatedString {
                append(if (backOn == "tomorrow") "Back tomorrow at " else "Back on $backOn at ")
                withStyle(SpanStyle(fontFamily = Vectra.fonts.mono, fontWeight = Weight.Bold, color = d.room)) { append(backAt) }
            }
            VText(text, style = vtext(13.sp to 18.sp), color = d.secondary, align = TextAlign.Center)
        }
    }
}

/** First load with nothing cached: two cards in the real shape, no shimmer. */
@Composable
fun DayListLoading(modifier: Modifier = Modifier) {
    val d = rememberDayColors()
    Column(modifier.fillMaxWidth().semantics { contentDescription = "Loading today's schedule" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlaceholderCard(d)
        Box(Modifier.fillMaxWidth().height(44.dp).background(d.placeholderStrip, StripShape))
        PlaceholderCard(d)
    }
}

@Composable
private fun PlaceholderCard(d: DayColors) {
    val bar = RoundedCornerShape(6.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(d.card, CardShape)
            .specularBorder(CardShape, d.border, d.border)
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(64.dp, 10.dp).background(d.placeholder, bar))
                Box(Modifier.fillMaxWidth(0.7f).height(16.dp).background(d.placeholder, bar))
                Box(Modifier.fillMaxWidth(0.45f).height(12.dp).background(d.placeholder, bar))
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(44.dp, 14.dp).background(d.placeholder, bar))
                Box(Modifier.size(44.dp, 14.dp).background(d.placeholder, bar))
            }
        }
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxWidth(0.55f).height(12.dp).background(d.placeholder, bar))
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(36.dp).background(d.placeholder, RoundedCornerShape(50)))
        }
    }
}

/** A day's title ("Today", "Monday") with its date on the right, above the day's cards. */
@Composable
fun DayListHeader(title: String, date: String, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp).semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        VText(title, Modifier.alignByBaseline(), vtext(24.sp to 30.sp, Weight.Extrabold, (-0.02).em), c.textPrimary, maxLines = 1)
        VText(date, Modifier.alignByBaseline(), vtext(13.sp to 18.sp, Weight.Medium).tnum(), c.textSecondary, maxLines = 1)
    }
}

// ---- The list ---------------------------------------------------------------------------------

/** One-line wrap-up of a finished day: "4 classes and a shift, all checked in". */
internal fun daySummary(classes: Int, shifts: Int, tasks: Int, checkedIn: Int, trackable: Int): String {
    fun count(n: Int, one: String, many: String, article: String) = if (n == 1) "$article $one" else "$n $many"
    val parts = buildList {
        if (classes > 0) add(if (classes == 1) "1 class" else "$classes classes")
        if (shifts > 0) add(count(shifts, "shift", "shifts", "a"))
        if (tasks > 0) add(count(tasks, "task", "tasks", "a"))
    }
    val what = when (parts.size) {
        0 -> "Nothing left"
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }.replaceFirstChar { it.uppercase() }
    return when {
        trackable == 0 -> what
        checkedIn == trackable -> if (trackable == 1) "$what, checked in" else "$what, all checked in"
        else -> "$what, $checkedIn of $trackable checked in"
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
    onToggleTaskCompleted: ((ScheduleItem) -> Unit)? = null,
    onResolveSyncConflict: ((id: String, resolution: String) -> Unit)? = null,
    syncConflictIds: Set<String> = emptySet(),
    needsAcknowledgmentIds: Set<String> = emptySet(),
    classConflictsByEventId: Map<String, List<ScheduleConflict>> = emptyMap(),
    itemConflictsById: Map<String, List<ScheduleConflict>> = emptyMap(),
    timeFormat: TimeFormat = Vectra.appearance.timeFormat,
    intensiveCourses: Set<String>? = null,
    collapseFinished: Boolean = false,
    offline: Boolean = false,
    emptyPlaceholder: (@Composable () -> Unit)? = null,
) {
    val d = rememberDayColors()
    val clock = rememberMinuteClock()
    val now = nowInBudapest(Instant.fromEpochMilliseconds(clock))
    val isToday = dateStr == now.ymd
    val nowMinute = if (isToday) now.minuteOfDay else null

    // Each class's status for this date (a composable read, so it follows the tracker).
    val statuses = HashMap<String, AttendanceStatus>()
    for (e in events) key(e.id, e.startTime) { statuses["${e.id}:${e.startTime}"] = tracker.attendanceStatus(e, dateStr) }

    val entries = remember(events, items, statuses.toMap()) {
        events.mapNotNull { e ->
            val s = minuteOf(e.startTime) ?: return@mapNotNull null
            val end = minuteOf(e.endTime) ?: return@mapNotNull null
            DayEntry.Class(e, s, end, statuses["${e.id}:${e.startTime}"] == AttendanceStatus.Cancelled)
        } + items.mapNotNull { o ->
            val s = minuteOf(o.startTime) ?: return@mapNotNull null
            val end = minuteOf(o.endTime) ?: return@mapNotNull null
            DayEntry.Item(o, s, end)
        }
    }
    if (entries.isEmpty()) {
        emptyPlaceholder?.invoke()
        return
    }
    val plan = remember(entries, nowMinute, collapseFinished) { buildDayPlan(entries, nowMinute, collapseFinished) }

    // Check-ins made offline show a clock until the connection is back.
    val pendingSync = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(offline) { if (!offline) pendingSync.clear() }
    var stackOpen by remember(dateStr) { mutableStateOf(false) }

    fun statusOf(e: DayEntry): AttendanceStatus? = when (e) {
        is DayEntry.Class -> if (e.event.isExam()) null else statuses["${e.event.id}:${e.event.startTime}"] ?: AttendanceStatus.Unmarked
        is DayEntry.Item -> if (e.occurrence.item.isTask && e.occurrence.item.completed == true) AttendanceStatus.Present
        else if (e.occurrence.item.isTask) AttendanceStatus.Unmarked else null
    }
    fun setStatus(e: DayEntry.Class, s: AttendanceStatus) {
        tracker.setAttendanceStatus(e.event, dateStr, s)
        if (offline) pendingSync[e.key] = true
    }
    val canCheckIn: (DayEntry.Class) -> Boolean = { isToday && !it.event.isExam() }

    val visible = plan.rows.size + (if (plan.finished.isNotEmpty()) 1 else 0)
    val showLine = visible > 1 && !plan.dayIsOver
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                if (!showLine) return@drawBehind
                // One line down the middle; the opaque cards hide it, so it shows only in the gaps.
                val inset = 24.dp.toPx()
                if (size.height > inset * 2) {
                    drawLine(d.line, Offset(size.width / 2f, inset), Offset(size.width / 2f, size.height - inset), 2.dp.toPx(), cap = StrokeCap.Round)
                }
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (plan.finished.isNotEmpty()) {
            FinishedStack(
                plan.finished,
                open = stackOpen,
                onToggle = { stackOpen = !stackOpen },
                d = d,
                timeFormat = timeFormat,
                statusOf = { e ->
                    when {
                        e is DayEntry.Item && !e.occurrence.item.isTask -> null
                        else -> statusOf(e)
                    }
                },
                onDisc = { e ->
                    when {
                        e is DayEntry.Class && canCheckIn(e) -> {
                            val s = statuses["${e.event.id}:${e.event.startTime}"] ?: AttendanceStatus.Unmarked
                            ({ setStatus(e, nextStatus(s)) })
                        }
                        e is DayEntry.Item && e.occurrence.item.isTask && onToggleTaskCompleted != null -> ({ onToggleTaskCompleted(e.occurrence.item) })
                        else -> null
                    }
                },
            )
        }
        for (row in plan.rows) {
            key(row.key) {
                when (row) {
                    is DayRow.Break -> BreakStrip(row, nowMinute, d, timeFormat)
                    is DayRow.Entry -> when (val e = row.entry) {
                        is DayEntry.Class -> ClassCard(
                            entry = e,
                            status = statuses["${e.event.id}:${e.event.startTime}"] ?: AttendanceStatus.Unmarked,
                            d = d,
                            focus = plan.focusKey == e.key,
                            nowMinute = nowMinute,
                            overlap = plan.overlaps[e.key],
                            repoClash = classConflictsByEventId["$dateStr:${e.event.id}"].orEmpty().isNotEmpty(),
                            canCheckIn = canCheckIn(e),
                            pendingSync = pendingSync[e.key] == true,
                            timeFormat = timeFormat,
                            intensiveCourses = intensiveCourses,
                            onClick = onEventClick,
                            streak = { tracker.presentStreak(e.event, dateStr) },
                            onSetStatus = { setStatus(e, it) },
                        )
                        is DayEntry.Item -> {
                            val id = e.occurrence.item.id
                            ItemCard(
                                entry = e,
                                d = d,
                                overlap = plan.overlaps[e.key],
                                conflicts = itemConflictsById["${e.occurrence.date}:$id"].orEmpty(),
                                syncConflict = id in syncConflictIds,
                                waitingForAck = id in needsAcknowledgmentIds,
                                timeFormat = timeFormat,
                                onClick = onItemClick,
                                onToggleComplete = onToggleTaskCompleted,
                                onResolveSyncConflict = onResolveSyncConflict?.let { cb -> { r: String -> cb(id, r) } },
                            )
                        }
                    }
                }
            }
        }
        if (plan.dayIsOver) {
            val all = plan.finished
            val classes = all.filterIsInstance<DayEntry.Class>().filter { !it.cancelled }
            val trackable = classes.filter { !it.event.isExam() }
            val items = all.filterIsInstance<DayEntry.Item>()
            DayDoneCard(
                daySummary(
                    classes = classes.size,
                    shifts = items.count { !it.occurrence.item.isTask },
                    tasks = items.count { it.occurrence.item.isTask },
                    checkedIn = trackable.count { statuses["${it.event.id}:${it.event.startTime}"] == AttendanceStatus.Present },
                    trackable = trackable.size,
                ),
                d,
            )
        }
    }
}

/**
 * When things start again after an empty day: ("tomorrow" or a weekday name, "HH:mm") for the
 * first class or item in the next two weeks, or null when there is none.
 */
fun nextScheduledStart(events: List<ScheduleEvent>, items: List<ScheduleItem>, today: String): Pair<String, String>? {
    val start = parseYmd(today) ?: return null
    val index = ScheduleEventIndex(events)
    for (offset in 1..14) {
        val date = start.plus(DatePeriod(days = offset))
        val ymd = date.ymd()
        val dow = date.dayOfWeek.daysSinceSunday
        val classTimes = events.filter { it.dayOfWeek == dow && classOccursOnDate(it, ymd, index) }.mapNotNull { e -> minuteOf(e.startTime)?.let { it to e.startTime } }
        val itemTimes = getScheduleItemOccurrencesForDate(items, ymd).filter { !it.continuation }.mapNotNull { o -> minuteOf(o.startTime)?.let { it to o.startTime } }
        val first = (classTimes + itemTimes).minByOrNull { it.first } ?: continue
        val day = when {
            offset == 1 -> "tomorrow"
            offset < 7 -> getDayName(dow)
            else -> "${getDayName(dow)} ${formatDayMonth(ymd)}"
        }
        return day to first.second
    }
    return null
}
