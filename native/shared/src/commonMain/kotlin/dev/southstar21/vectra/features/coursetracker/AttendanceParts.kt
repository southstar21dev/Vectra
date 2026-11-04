package dev.southstar21.vectra.features.coursetracker

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.StatusLabel
import dev.southstar21.vectra.core.designsystem.components.StatusTone
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.mixTransparent
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.rgba
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.time.formatTime
import dev.southstar21.vectra.core.util.roundHalfUpToInt
import dev.southstar21.vectra.core.preferences.TimeFormat
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

/** Shrinks the reported height by [dy] on both edges (a negative vertical margin). */
internal fun Modifier.negativeVerticalMargin(dy: Dp): Modifier = layout { m, c ->
    val p = m.measure(c)
    val d = dy.roundToPx()
    layout(p.width, (p.height - 2 * d).coerceAtLeast(0)) { p.place(0, -d) }
}

// ---------------------------------------------------------------------------
// Postage stamp and date tile
// ---------------------------------------------------------------------------

/**
 * A postage stamp: a surface tile with a dashed accent frame — or, for today,
 * a solid accent frame with an accent glow (TODAY_STAMP_GLOW).
 */
@Composable
internal fun TrackerStamp(
    modifier: Modifier = Modifier,
    innerModifier: Modifier = Modifier,
    today: Boolean = false,
    frameRadius: Dp = 4.dp,
    gap: Dp = 2.dp,
    contentPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Vectra.colors
    val outer = Radius.Tag
    val shadows = buildList {
        if (today) add(BoxShadow(blur = 14f, color = c.accentPrimary.mixTransparent(55f)))
        add(BoxShadow(y = 6f, blur = 16f, color = rgba(15, 23, 42, 0.14f)))
    }
    val frame = if (today) c.accentPrimary else c.accentPrimary.copy(alpha = 0.55f)
    Box(modifier.boxShadow(outer, shadows).background(c.surfaceTertiary, outer).padding(4.dp)) {
        Column(
            innerModifier.drawBehind {
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    frame,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(frameRadius.toPx()),
                    style = Stroke(w, pathEffect = if (today) null else PathEffect.dashPathEffect(floatArrayOf(2 * w, w))),
                )
            }.padding(horizontal = contentPadding),
            verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** A date as a postage stamp — month over day over weekday. Decorative. */
@Composable
internal fun DateTile(date: String?, today: Boolean = false, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    val parts = tileParts(date)
    TrackerStamp(modifier, Modifier.size(48.dp, 58.dp), today = today, frameRadius = 2.dp, gap = 3.dp) {
        if (parts != null) {
            VText(parts.month, style = vtext(10.sp to 10.sp, Weight.Extrabold, 0.12.em), color = c.accentPrimary)
            VText(parts.day, style = vtext(TypeScale.Subtitle.first to TypeScale.Subtitle.first, Weight.Black).tnum(), color = c.textPrimary)
            VText(parts.weekday, style = vtext(11.sp to 11.sp, Weight.Semibold), color = c.textSecondary)
        } else {
            VIcon(RemixIcons.CalendarLine, size = 20.dp, tint = c.textMuted)
        }
    }
}

// ---------------------------------------------------------------------------
// Session status and its toggle
// ---------------------------------------------------------------------------

internal data class StatusButtonSpec(val status: AttendanceStatus, val label: String, val tone: StatusTone)

/** The four marks, in display order. */
internal val StatusButtons = listOf(
    StatusButtonSpec(AttendanceStatus.Present, "Present", StatusTone.Emerald),
    StatusButtonSpec(AttendanceStatus.Absent, "Absent", StatusTone.Red),
    StatusButtonSpec(AttendanceStatus.Excused, "Excused", StatusTone.Amber),
    StatusButtonSpec(AttendanceStatus.Cancelled, "No class", StatusTone.Neutral),
)

internal fun statusIcon(status: AttendanceStatus) = when (status) {
    AttendanceStatus.Present -> RemixIcons.CheckLine
    AttendanceStatus.Absent -> RemixIcons.CloseLine
    AttendanceStatus.Excused -> RemixIcons.ErrorWarningLine
    else -> RemixIcons.CalendarCloseLine
}

internal val StatusLabels = mapOf(
    AttendanceStatus.Present to "Present",
    AttendanceStatus.Absent to "Absent",
    AttendanceStatus.Excused to "Excused",
    AttendanceStatus.Cancelled to "No class",
    AttendanceStatus.Unmarked to "Not marked",
)

private fun markFill(status: AttendanceStatus) = when (status) {
    AttendanceStatus.Present -> MarkFill.Present
    AttendanceStatus.Absent -> MarkFill.Absent
    AttendanceStatus.Excused -> MarkFill.Excused
    else -> MarkFill.Cancelled
}

/**
 * The four-state control: tap the lit mark again to clear it. `labels` (the
 * Today card) lights all four while the session waits for a mark; `icons` (a
 * ledger row) stays quiet until one is chosen.
 */
@Composable
internal fun StatusToggle(
    session: AttendanceSession,
    onSetStatus: (String, AttendanceStatus) -> Unit,
    labels: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = Vectra.colors
    val unmarked = session.status == AttendanceStatus.Unmarked
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (labels) 6.dp else 4.dp)) {
        for (btn in StatusButtons) {
            val active = session.status == btn.status
            val lit = active || (labels && unmarked)
            val shape = Radius.Chip
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .weight(1f)
                    .pressScale(interaction)
                    .height(if (labels) 44.dp else 36.dp)
                    .then(if (lit) Modifier.markDepth(shape, markFill(btn.status)) else Modifier.markGlass(shape))
                    .tappable(role = Role.Button, highlight = false, interactionSource = interaction) {
                        onSetStatus(session.id.ifEmpty { session.date }, if (active) AttendanceStatus.Unmarked else btn.status)
                    }
                    .semantics { contentDescription = if (active) "${btn.label} — tap to clear" else "Mark ${btn.label.lowercase()}" },
                contentAlignment = Alignment.Center,
            ) {
                if (labels) {
                    VText(btn.label, style = vtext(TypeScale.Caption, if (lit) Weight.Bold else Weight.Semibold), color = if (lit) Color.White else c.textSecondary, maxLines = 1)
                } else {
                    val idle = when (btn.status) {
                        AttendanceStatus.Present -> c.tints.emerald.text
                        AttendanceStatus.Absent -> c.tints.red.text
                        AttendanceStatus.Excused -> c.tints.amber.text
                        else -> c.textSecondary
                    }
                    VIcon(statusIcon(btn.status), size = 18.dp, tint = if (lit) Color.White else idle)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Attendance strip
// ---------------------------------------------------------------------------

internal enum class DotKind { Present, Absent, Excused, Cancelled, Today, Open }

private fun dotKind(session: AttendanceSession, todayIso: String): DotKind = when (session.status) {
    AttendanceStatus.Present -> DotKind.Present
    AttendanceStatus.Absent -> DotKind.Absent
    AttendanceStatus.Excused -> DotKind.Excused
    AttendanceStatus.Cancelled -> DotKind.Cancelled
    AttendanceStatus.Unmarked -> if (session.date == todayIso) DotKind.Today else DotKind.Open
}

/** One dot, filled by how the session was marked; "No class" is hatched. */
@Composable
internal fun Dot(kind: DotKind, diameter: Dp, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    val base = modifier.size(diameter).clip(CircleShape)
    when (kind) {
        DotKind.Present -> Box(base.background(Fills.Emerald500))
        DotKind.Absent -> Box(base.background(Fills.Red500))
        DotKind.Excused -> Box(base.background(Fills.Amber500))
        DotKind.Open -> Box(base.background(c.borderSubtle))
        DotKind.Today -> Box(modifier.size(diameter).border(2.dp, c.accentPrimary, CircleShape))
        DotKind.Cancelled -> Box(
            base.drawBehind {
                // repeating-linear-gradient(45deg, slate-400/30 0 1.5px, transparent 1.5px 4px)
                val stripe = Fills.Slate400.copy(alpha = 0.3f)
                val period = 4.dp.toPx() * 1.41421356f
                val w = 1.5.dp.toPx()
                clipRect {
                    var x = -size.height
                    while (x < size.width + size.height) {
                        drawLine(stripe, Offset(x, size.height), Offset(x + size.height, 0f), w)
                        x += period
                    }
                }
            },
        )
    }
}

/**
 * The semester at a glance for one class type: one dot per session, spread
 * edge to edge (up to 20dp); tapping a dot brings its row into view.
 */
@Composable
internal fun AttendanceStrip(
    label: String,
    tone: StatusTone,
    sessions: List<AttendanceSession>,
    todayIso: String,
    value: String?,
    describe: (AttendanceSession) -> String,
    onSelect: (AttendanceSession) -> Unit,
) {
    val c = Vectra.colors
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(tone.ink(), CircleShape))
            VText(label, Modifier.weight(1f), vtext(TypeScale.Body, Weight.Bold), c.textPrimary, maxLines = 1)
            if (value != null) VText(value, style = vtext(TypeScale.Body, Weight.Extrabold).tnum(), color = c.textPrimary)
        }
        BoxWithConstraints(Modifier.padding(top = 10.dp).fillMaxWidth()) {
            val n = sessions.size.coerceAtLeast(1)
            val dot = min(20.dp, (maxWidth - 3.dp * (n - 1)) / n)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (s in sessions) {
                    val kind = dotKind(s, todayIso)
                    val markedToday = s.date == todayIso && kind != DotKind.Today
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .pressScale(interaction, 0.9f)
                            .then(if (markedToday) Modifier.drawBehind {
                                val o = 2.dp.toPx() + 1.dp.toPx()
                                drawCircle(c.accentPrimary, size.minDimension / 2 + o, style = Stroke(2.dp.toPx()))
                            } else Modifier)
                            .tappable(role = Role.Button, highlight = false, interactionSource = interaction) { onSelect(s) }
                            .semantics { contentDescription = describe(s) },
                    ) { Dot(kind, dot) }
                }
            }
        }
    }
}

/** Key for the dots: every kind, always, in three columns spread edge to edge. */
@Composable
internal fun AttendanceStripLegend() {
    val c = Vectra.colors
    val cols = listOf(
        listOf(DotKind.Present to "Present", DotKind.Today to "Today"),
        listOf(DotKind.Absent to "Absent", DotKind.Cancelled to "No class"),
        listOf(DotKind.Excused to "Excused", DotKind.Open to "Pending"),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (col in cols) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((kind, label) in col) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(kind, 10.dp)
                        VText(label, style = vtext(TypeScale.Caption), color = c.textSecondary, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Attendance standing card
// ---------------------------------------------------------------------------

internal data class StandingStrip(
    val label: String,
    val tone: StatusTone,
    val stats: AttendanceStats,
    val sessions: List<AttendanceSession>,
)

/** Quotas above this many absences drop the bars; the number alone carries it. */
private const val MaxBars = 12

/** "Can I still miss one?": skips left beside a bar per allowed absence, then each type's dots. */
@Composable
internal fun AttendanceStandingCard(
    maxAbsences: Int?,
    stats: AttendanceStats,
    riskTier: AttendanceRiskTier,
    strips: List<StandingStrip>,
    todayIso: String,
    describe: (AttendanceSession) -> String,
    onSelectSession: (AttendanceSession) -> Unit,
) {
    val c = Vectra.colors
    val used = stats.absentCount
    val over = if (maxAbsences != null) maxOf(0, used - maxAbsences) else 0
    val remaining = if (maxAbsences != null) maxOf(0, maxAbsences - used) else 0
    var value: String?
    var suffix: String? = null
    val caption: String
    var valueColor = c.textPrimary
    when {
        maxAbsences == null -> {
            value = if (stats.activeCount > 0) "${roundHalfUpToInt(stats.attendancePercent)}%" else null
            caption = if (value != null) "attended" else "Nothing marked yet"
        }
        over > 0 -> { value = "+$over"; caption = "over the limit"; valueColor = c.tints.red.text }
        maxAbsences == 0 -> { value = null; caption = "No skips allowed" }
        else -> {
            value = remaining.toString()
            suffix = "/$maxAbsences"
            caption = if (remaining == 1) "skip left" else "skips left"
            valueColor = if (riskTier == AttendanceRiskTier.Warning) c.tints.amber.text else c.tints.emerald.text
        }
    }
    val showBars = maxAbsences != null && maxAbsences in 1..MaxBars
    val narrow = maxAbsences != null && maxAbsences > 6
    val shape = Radius.Card
    Column(Modifier.fillMaxWidth().glassDepth(shape).padding(20.dp).semantics { contentDescription = "Attendance standing" }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (value == null) {
                    VText(caption, style = vtext(TypeScale.Title, Weight.Black, Tracking.Tight), color = c.textPrimary)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.alignByBaseline()) {
                            VText(value, Modifier.alignByBaseline(), vtext(44.sp to 44.sp, Weight.Black, Tracking.Tight).tnum(), valueColor)
                            if (suffix != null) VText(suffix, Modifier.alignByBaseline(), vtext(TypeScale.Title, Weight.Bold), c.textMuted)
                        }
                        VText(caption, Modifier.alignByBaseline(), vtext(15.sp to 22.sp, Weight.Bold), c.textPrimary)
                    }
                }
            }
            if (showBars) {
                Row(
                    Modifier.semantics { contentDescription = "Absences: ${minOf(used, maxAbsences)} of $maxAbsences used" },
                    horizontalArrangement = Arrangement.spacedBy(if (narrow) 4.dp else 6.dp),
                ) {
                    repeat(maxAbsences) { i ->
                        val fill by animateColorAsState(if (i < used) Fills.Red500 else Fills.Emerald500, tween(500), label = "bar")
                        val bar = Radius.Tag
                        Box(
                            Modifier.width(if (narrow) 8.dp else 16.dp).height(40.dp)
                                .background(fill, bar)
                                .drawBehind { drawLine(rgba(255, 255, 255, 0.35f), Offset(6.dp.toPx(), 0.5.dp.toPx()), Offset(size.width - 6.dp.toPx(), 0.5.dp.toPx()), 1.dp.toPx()) },
                        )
                    }
                }
            }
        }
        if (strips.isNotEmpty()) {
            Column(
                Modifier.padding(top = 18.dp).fillMaxWidth().topRule(c.borderSubtle).padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                for (row in strips) {
                    AttendanceStrip(
                        label = row.label,
                        tone = row.tone,
                        sessions = row.sessions,
                        todayIso = todayIso,
                        value = if (row.stats.activeCount > 0) "${roundHalfUpToInt(row.stats.attendancePercent)}%" else null,
                        describe = describe,
                        onSelect = onSelectSession,
                    )
                }
            }
            Box(Modifier.padding(top = 16.dp).fillMaxWidth().topRule(c.borderSubtle).padding(top = 12.dp)) { AttendanceStripLegend() }
        }
    }
}

// ---------------------------------------------------------------------------
// Session ledger row
// ---------------------------------------------------------------------------

/**
 * One row of the semester ledger: the date tile, the week and type, and the
 * four marks underneath so any session is changed in place with one tap.
 */
@Composable
internal fun SessionLedgerRow(
    session: AttendanceSession,
    title: String,
    badgeLabel: String,
    badgeTone: StatusTone,
    todayIso: String,
    timeFormat: TimeFormat,
    flash: Boolean,
    onSetStatus: (String, AttendanceStatus) -> Unit,
    onRequestDelete: (AttendanceSession) -> Unit,
) {
    val c = Vectra.colors
    val isToday = session.date == todayIso
    val wash by animateColorAsState(
        if (flash) c.accentPrimary.copy(alpha = 0.1f) else Color.Transparent,
        tween(if (flash) 150 else 700),
        label = "flash",
    )
    val dateLabel = formatSessionDate(session.date, todayIso)
    val time = session.startTime.nonEmpty()?.let { formatTime(it, timeFormat) }.orEmpty()
    Row(
        Modifier.fillMaxWidth().background(wash).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DateTile(session.date, today = isToday, modifier = Modifier.align(Alignment.CenterVertically))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).semantics { contentDescription = "$title, $dateLabel${if (time.isNotEmpty()) " $time" else ""}" },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VText(title, Modifier.weight(1f, fill = false), vtext(TypeScale.Body, Weight.Bold), c.textPrimary, maxLines = 1)
                    if (session.isCustom == true) StatusLabel("Custom", tone = StatusTone.Amber)
                }
                Row(Modifier.negativeVerticalMargin(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusLabel(badgeLabel, tone = badgeTone)
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .pressScale(interaction)
                            .size(32.dp)
                            .clip(CircleShape)
                            .tappable(role = Role.Button, highlight = false, interactionSource = interaction) { onRequestDelete(session) }
                            .semantics { contentDescription = "Delete $title on $dateLabel" },
                        contentAlignment = Alignment.Center,
                    ) { VIcon(RemixIcons.DeleteBinLine, size = 14.dp, tint = c.textMuted) }
                }
            }
            if (!session.notes.isNullOrEmpty()) VText(session.notes, style = vtext(TypeScale.Caption), color = c.textSecondary, maxLines = 1)
            StatusToggle(session, onSetStatus, labels = false)
        }
    }
}

// ---------------------------------------------------------------------------
// Today's session card
// ---------------------------------------------------------------------------

/**
 * Today's session, marked in one tap: a tilted "Today" stamp naming the class,
 * who teaches it, where and when, and the four marks.
 */
@Composable
fun TodaySessionCard(
    session: AttendanceSession,
    typeLabel: String,
    weekTitle: String,
    instructor: String?,
    location: String?,
    timeFormat: TimeFormat,
    onSetStatus: (String, AttendanceStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Vectra.colors
    val time = session.startTime.nonEmpty()?.let { start ->
        formatTime(start, timeFormat) + (session.endTime.nonEmpty()?.let { "–" + formatTime(it, timeFormat) } ?: "")
    }.orEmpty()
    val shape = Radius.Panel
    Box(modifier.fillMaxWidth().padding(top = 28.dp).semantics { contentDescription = "Today's ${typeLabel.lowercase()}" }) {
        Column(Modifier.fillMaxWidth().glassDepth(shape).padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 64.dp)) {
            StatusToggle(session, onSetStatus, labels = true)
        }
        TrackerStamp(
            Modifier.offset(x = 14.dp, y = (-26).dp).graphicsLayer { rotationZ = -4f },
            Modifier.size(84.dp, 72.dp),
            today = true,
            gap = 4.dp,
            contentPadding = 4.dp,
        ) {
            VText("TODAY", style = vtext(TypeScale.Caption.first to TypeScale.Caption.first, Weight.Extrabold, 0.12.em), color = c.accentPrimary)
            VText(
                typeLabel,
                style = vtext(if (typeLabel.length > 8) TypeScale.Body.first to 17.5.sp else 17.sp to 21.25.sp, Weight.Black, Tracking.Tight),
                color = c.textPrimary,
                maxLines = 1,
            )
        }
        Column(
            Modifier.padding(start = 122.dp, end = 14.dp, top = 12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VText(instructor.nonEmpty() ?: weekTitle, style = vtext(TypeScale.Body, Weight.Bold), color = c.textPrimary, maxLines = 1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val where = location.nonEmpty()?.let { "Room $it" } ?: if (!instructor.isNullOrEmpty()) weekTitle else ""
                VText(where, Modifier.weight(1f), vtext(13.sp to 18.sp), c.textSecondary, maxLines = 1)
                if (time.isNotEmpty()) VText(time, style = vtext(13.sp to 18.sp, mono = true).tnum(), color = c.textSecondary, maxLines = 1)
            }
        }
    }
}
