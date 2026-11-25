package dev.southstar21.vectra.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import dev.southstar21.vectra.android.R
import dev.southstar21.vectra.features.widgets.AgendaCut
import dev.southstar21.vectra.features.widgets.FootNote
import dev.southstar21.vectra.features.widgets.FootNoteType
import dev.southstar21.vectra.features.widgets.footNotes
import dev.southstar21.vectra.features.widgets.nextDayNote
import dev.southstar21.vectra.features.widgets.taskNote
import dev.southstar21.vectra.features.widgets.SpineRow
import dev.southstar21.vectra.features.widgets.WidgetKind
import dev.southstar21.vectra.features.widgets.WidgetSession
import dev.southstar21.vectra.features.widgets.agendaCuts
import dev.southstar21.vectra.features.widgets.clockLabel
import dev.southstar21.vectra.features.widgets.dayDistanceLabel
import dev.southstar21.vectra.features.widgets.dayMonthLabel
import dev.southstar21.vectra.features.widgets.dayShown
import dev.southstar21.vectra.features.widgets.doneOf
import dev.southstar21.vectra.features.widgets.emptyMessage
import dev.southstar21.vectra.features.widgets.place
import dev.southstar21.vectra.features.widgets.progress
import dev.southstar21.vectra.features.widgets.remainingLabel
import dev.southstar21.vectra.features.widgets.spanLabel
import dev.southstar21.vectra.features.widgets.agendaRows
import dev.southstar21.vectra.features.widgets.dayHeading
import dev.southstar21.vectra.features.widgets.weekdayLongLabel

/** Large: the whole day on one time line, with breaks and what's due. */
class AgendaWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetTheme { AgendaContent(context, rememberFrame(context)) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { PreviewTheme(360.dp, 300.dp) { AgendaContent(context, sampleFrame()) } }
    }
}

@Composable
private fun AgendaContent(context: Context, frame: WidgetFrame) {
    val snap = frame.snapshot
    if (snap?.signedIn != true) {
        EmptyState(context, snap.emptyMessage("Open Vectra and sign in to see today here."))
        return
    }
    val day = snap.dayShown(frame.today)
    val rows = snap.agendaRows(day, frame.nowMs)
    val (done, total) = snap.doneOf(day, frame.nowMs)
    val taskLine = snap.taskNote(day, frame.nowMs)
    val nextLine = snap.nextDayNote(day, frame.today)
    val size = LocalSize.current
    val b = Budget(context)
    val width = size.width.value - 34f
    val space = size.height.value - 27f - (b.line(11f) + b.line(24f)) - 9f
    val pick = pickCut(agendaCuts(rows), taskLine, nextLine, b, width, space)
    WidgetSurface(context, padding = 0.dp) {
        Column(GlanceModifier.fillMaxSize().padding(start = 16.dp, top = 14.dp, end = 18.dp, bottom = 13.dp)) {
            Header(day, frame.today, done, total)
            Spacer(GlanceModifier.height(9.dp))
            if (rows.isEmpty()) Quiet("Nothing on today.", 12f)
            // The rows in one column: Glance draws at most ten children per column.
            Column(GlanceModifier.fillMaxWidth()) {
                pick.cut.shown.take(10).forEachIndexed { i, row ->
                    val last = i == pick.cut.shown.lastIndex || pick.cut.shown[i + 1] is SpineRow.Day
                    if (row is SpineRow.Day) {
                        DayLabel("${dayDistanceLabel(frame.today, row.day)}, ${dayHeading(row.day)}", pick.heights[i])
                    } else {
                        AgendaRow(row, frame.nowMs, pick.heights[i], last, pick.compact, width)
                    }
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            val lines = footNotes(pick.cut, taskLine, nextLine)
            if (lines.isNotEmpty()) Footer(lines)
        }
    }
}

@Composable
private fun DayLabel(text: String, height: Float) {
    Row(GlanceModifier.fillMaxWidth().height(height.dp).padding(start = 72.dp, top = 4.dp)) { Micro(text) }
}

@Composable
private fun Header(day: String, today: String, done: Int, total: Int) {
    val p = LocalWPalette.current
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(GlanceModifier.defaultWeight()) {
            Text((if (day == today) weekdayLongLabel(day) else dayDistanceLabel(today, day)).uppercase(), style = style(11.sp, FontWeight.Bold, p.accent), maxLines = 1)
            Text(dayMonthLabel(day), style = style(24.sp, FontWeight.Bold, p.text), maxLines = 1)
        }
        if (total > 0) {
            Column(horizontalAlignment = Alignment.End) {
                Quiet("$done of $total done", 12f)
                Spacer(GlanceModifier.height(4.dp))
                Row {
                    repeat(minOf(total, 8)) { i ->
                        if (i > 0) Spacer(GlanceModifier.width(3.dp))
                        Box(GlanceModifier.width(12.dp).height(4.dp).cornerRadius(2.dp).background(if (i < done) p.check else p.track)) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaRow(row: SpineRow, nowMs: Long, height: Float, last: Boolean, compact: Boolean, width: Float) {
    when (row) {
        is SpineRow.Break -> BreakRow(row.minutes, height)
        is SpineRow.Past -> SessionRow(row.session, nowMs, height, last, compact, width, done = true)
        is SpineRow.Now -> SessionRow(row.session, nowMs, height, last, compact, width, live = true)
        is SpineRow.Next -> SessionRow(row.session, nowMs, height, last, compact, width)
        is SpineRow.Day -> Unit
    }
}

@Composable
private fun BreakRow(minutes: Int, height: Float) {
    val p = LocalWPalette.current
    Row(GlanceModifier.fillMaxWidth().height(height.dp), verticalAlignment = Alignment.Top) {
        Spacer(GlanceModifier.width(50.dp))
        Column(GlanceModifier.width(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { SpineLine(height.dp, dashed = true) }
        Spacer(GlanceModifier.width(10.dp))
        Quiet("Break, ${spanOf(minutes)}", 11f, p.muted)
    }
}

@Composable
private fun SessionRow(s: WidgetSession, nowMs: Long, height: Float, last: Boolean, compact: Boolean, width: Float, done: Boolean = false, live: Boolean = false) {
    val p = LocalWPalette.current
    val nameLines = Budget(LocalContext.current).lines(s.title, 14f, width - 72f, max = 2)
    // Compact cuts leave the rooms out, except the class on now's.
    val showRoom = !done && (live || !compact) && s.place.isNotBlank()
    Row(GlanceModifier.fillMaxWidth().height(height.dp)) {
        Column(GlanceModifier.width(40.dp), horizontalAlignment = Alignment.End) {
            Mono(clockLabel(s.startMs), color = if (done) p.muted else null)
            if (!done) Mono(clockLabel(s.endMs), 11f, p.muted, FontWeight.Medium)
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.width(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(GlanceModifier.height(2.dp))
            Node(s.kind, live, done)
            if (!last) SpineLine((height - 14f).dp)
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeWord(s.kind)
                if (live) {
                    Spacer(GlanceModifier.width(8.dp))
                    Text(remainingLabel(nowMs, s.endMs), style = style(11.sp, FontWeight.Bold, p.tone(s.kind)), maxLines = 1)
                }
            }
            Name(s.title, 14f, nameLines, dim = done)
            if (showRoom) RoomLine(s.location, s.group)
            if (live) {
                Spacer(GlanceModifier.height(5.dp))
                Bar(progress(s, nowMs), s.kind)
            }
        }
    }
}

private fun spanOf(minutes: Int): String = spanLabel(minutes * 60_000L)

@Composable
private fun Footer(lines: List<FootNote>) {
    val p = LocalWPalette.current
    Column(GlanceModifier.fillMaxWidth()) {
        Hair()
        Spacer(GlanceModifier.height(7.dp))
        lines.forEach { l ->
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val icon = l.type.icon()
                val kind = l.kind
                when {
                    icon != null -> Tinted(icon, l.kind?.let(p::tone) ?: p.muted, 13.dp)
                    kind != null -> Box(GlanceModifier.width(13.dp), contentAlignment = Alignment.Center) {
                        Box(GlanceModifier.width(6.dp).height(6.dp).cornerRadius(3.dp).background(p.tone(kind))) {}
                    }
                }
                Spacer(GlanceModifier.width(8.dp))
                Text(l.text, GlanceModifier.defaultWeight(), style(12.sp, FontWeight.Medium, p.secondary), maxLines = 2)
                Spacer(GlanceModifier.width(6.dp))
                Mono(l.time, 11f, p.muted)
            }
            Spacer(GlanceModifier.height(5.dp))
        }
    }
}

/** The drawable a footer line leads with; "then" lines lead with a type dot instead. */
private fun FootNoteType.icon(): Int? = when (this) {
    FootNoteType.Then -> null
    FootNoteType.Task -> R.drawable.ic_widget_task
    FootNoteType.NextDay -> R.drawable.ic_widget_calendar
}
