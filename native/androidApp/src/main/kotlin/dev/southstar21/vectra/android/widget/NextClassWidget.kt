package dev.southstar21.vectra.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import dev.southstar21.vectra.android.R
import dev.southstar21.vectra.features.widgets.NextUp
import dev.southstar21.vectra.features.widgets.WidgetKind
import dev.southstar21.vectra.features.widgets.WidgetSession
import dev.southstar21.vectra.features.widgets.clockLabel
import dev.southstar21.vectra.features.widgets.dayDistanceLabel
import dev.southstar21.vectra.features.widgets.dayMonthLabel
import dev.southstar21.vectra.features.widgets.doneOf
import dev.southstar21.vectra.features.widgets.emptyMessage
import dev.southstar21.vectra.features.widgets.examsAfter
import dev.southstar21.vectra.features.widgets.laterOn
import dev.southstar21.vectra.features.widgets.listedMs
import dev.southstar21.vectra.features.widgets.nextUp
import dev.southstar21.vectra.features.widgets.openTasks
import dev.southstar21.vectra.features.widgets.place
import dev.southstar21.vectra.features.widgets.progress
import dev.southstar21.vectra.features.widgets.ringLabel
import dev.southstar21.vectra.features.widgets.sessionsOn
import dev.southstar21.vectra.features.widgets.spanLabel
import dev.southstar21.vectra.features.widgets.weekdayLabel

/** Small: the class you're in or the next one on a ring, then what comes after. */
class NextClassWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetTheme { NextClassContent(context, rememberFrame(context)) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { PreviewTheme(170.dp, 170.dp) { NextClassContent(context, sampleFrame()) } }
    }
}

private class MiniItem(val time: String, val title: String, val kind: WidgetKind?)

private fun WidgetSession.mini() = MiniItem(clockLabel(listedMs), title, kind)

/** What comes after [s]: the rest of its day, then the next days' first classes with their weekday. */
private fun WidgetFrame.afterRows(s: WidgetSession): List<MiniItem> {
    val sameDay = snapshot.laterOn(s.day, nowMs, s).map { it.mini() }
    val later = snapshot?.sessions.orEmpty().filter { it.day > s.day }.take(4).map {
        MiniItem("${weekdayLabel(it.day)} ${clockLabel(it.listedMs)}", it.title, it.kind)
    }
    return (sameDay + later).take(4)
}

private const val HourMs = 3_600_000L
private const val WeekMs = 7 * 24 * HourMs

@Composable
private fun NextClassContent(context: Context, frame: WidgetFrame) {
    val now = frame.nowMs
    when (val up = frame.snapshot.nextUp(now, frame.today)) {
        NextUp.NoData -> EmptyState(context, frame.snapshot.emptyMessage("Open Vectra and sign in to see your classes here."))
        is NextUp.InClass -> {
            val s = up.session
            val (value, unit) = ringLabel(s.endMs - now, "LEFT")
            Small(context, RingSpec(progress(s, now), s.kind, value, unit), listOf(s.kind.label.uppercase(), "till ${clockLabel(s.endMs)}", s.location), s.title,
                s.teacher.ifBlank { s.group }, frame.afterRows(s), "Nothing else coming up")
        }
        is NextUp.Upcoming -> {
            val s = up.session
            val (value, unit) = ringLabel(s.startMs - now, "TO GO")
            Small(context, RingSpec((1f - (s.startMs - now).toFloat() / HourMs).coerceIn(0f, 1f), s.kind, value, unit),
                listOf(s.kind.label.uppercase(), "${clockLabel(s.startMs)}–${clockLabel(s.endMs)}", s.place), s.title, s.teacher,
                frame.afterRows(s), "Nothing else coming up")
        }
        is NextUp.ExamSoon -> {
            val e = up.exam
            val (value, unit) = ringLabel(e.startMs - now, "TO GO")
            Small(context, RingSpec((1f - (e.startMs - now).toFloat() / WeekMs).coerceIn(0f, 1f), WidgetKind.Exam, value, unit),
                listOf("EXAM", "${weekdayLabel(e.day)} ${clockLabel(e.startMs)}", e.location), e.title, "Lasts ${spanLabel(e.endMs - e.startMs)}",
                frame.snapshot.examsAfter(e, 3).map { MiniItem(dayMonthLabel(it.day), it.title, WidgetKind.Exam) }, "No more exams soon")
        }
        is NextUp.DayDone -> DayDone(context, frame, up)
    }
}

private class RingSpec(val fraction: Float, val kind: WidgetKind, val value: String?, val unit: String, val icon: Int? = null)

/** The small widgets' shape: the ring head, the name and a quiet line, then as many rows as fit. */
@Composable
private fun Small(context: Context, ring: RingSpec, side: List<String>, title: String, sub: String, rows: List<MiniItem>, fallback: String) {
    val size = LocalSize.current
    val b = Budget(context)
    val inner = DpSize(size.width - 26.dp, size.height - 26.dp)
    val nameLines = b.lines(title, 15f, inner.width.value, max = 2)
    val used = 50f + 7f + b.line(15f) * nameLines + (if (sub.isBlank()) 0f else b.line(11f)) + 12f
    // The name's room: the width less the time beside it ("12:30", or "Thu 08:00").
    fun nameWidth(item: MiniItem) = inner.width.value - item.time.length * 6.5f - 8f
    val fit = fitRows(rows.map { b.height(it.title, 12f, nameWidth(it), max = 2) + 4f }, inner.height.value - used)
    WidgetSurface(context) {
        Column(GlanceModifier.fillMaxSize()) {
            RingHead(context, ring, side)
            Spacer(GlanceModifier.height(7.dp))
            Name(title, 15f, maxLines = nameLines)
            if (sub.isNotBlank()) Quiet(sub)
            Spacer(GlanceModifier.defaultWeight())
            Hair()
            Spacer(GlanceModifier.height(5.dp))
            if (rows.isEmpty()) Quiet(fallback)
            // The rows in one column: Glance draws at most ten children per column.
            Column {
                rows.take(fit.coerceAtMost(5)).forEach {
                    MiniRow(it.time, it.title, it.kind, lines = b.lines(it.title, 12f, nameWidth(it), max = 2))
                    Spacer(GlanceModifier.height(4.dp))
                }
            }
        }
    }
}

/** How many of the rows, at these heights, fit in [space]. */
internal fun fitRows(heights: List<Float>, space: Float): Int {
    var left = space
    heights.forEachIndexed { i, h ->
        left -= h
        if (left < 0) return i
    }
    return heights.size
}

@Composable
private fun RingHead(context: Context, ring: RingSpec, side: List<String>) {
    val p = LocalWPalette.current
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Ring(context, ring.fraction, ring.kind, ring.value, ring.unit, ring.icon)
        Spacer(GlanceModifier.width(10.dp))
        Column {
            side.getOrNull(0)?.let { Mono(it, 10f, p.tone(ring.kind)) }
            side.getOrNull(1)?.let { Mono(it, 11f) }
            side.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { RoomLine(it) }
        }
    }
}

@Composable
private fun DayDone(context: Context, frame: WidgetFrame, up: NextUp.DayDone) {
    val (done, total) = frame.snapshot.doneOf(frame.today, frame.nowMs)
    val tasks = frame.snapshot.openTasks(frame.today, frame.nowMs).size
    val next = up.next
    val nextDay = next?.let { frame.snapshot.sessionsOn(it.day) }.orEmpty()
    // The next day first, then the days after it, so the widget stays full.
    val upcoming = next?.let { n -> frame.snapshot?.sessions.orEmpty().filter { it.day >= n.day } }.orEmpty().take(6)
    val size = LocalSize.current
    val b = Budget(context)
    val rowWidth = size.width.value - 26f - 40f
    val fit = fitRows(upcoming.map { b.height(it.title, 12f, rowWidth - if (it.day != next?.day) 26f else 0f, max = 2) + 4f }, size.height.value - 26f - 50f - 12f - 1f - b.line(10f) - 10f)
    WidgetSurface(context) {
        Column(GlanceModifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ring(context, if (up.hadToday) 1f else 0f, WidgetKind.Seminar, null, "", if (up.hadToday) R.drawable.ic_widget_check else R.drawable.ic_widget_calendar)
                Spacer(GlanceModifier.width(10.dp))
                Column {
                    Name(if (up.hadToday) "All done" else "Free today", 14f, maxLines = 1)
                    if (up.hadToday) Quiet("$done of $total classes")
                    Quiet(if (tasks == 0) "No tasks left" else if (tasks == 1) "1 task left" else "$tasks tasks left")
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            Hair()
            Spacer(GlanceModifier.height(6.dp))
            if (next == null) {
                Quiet("Nothing in the next two weeks.")
            } else {
                Row(GlanceModifier.fillMaxWidth()) {
                    Micro(dayDistanceLabel(frame.today, next.day))
                    Spacer(GlanceModifier.defaultWeight())
                    Quiet(if (nextDay.size == 1) "1 item" else "${nextDay.size} items", 10f)
                }
                Spacer(GlanceModifier.height(4.dp))
                Column {
                    upcoming.take(fit.coerceAtMost(5)).forEachIndexed { i, s ->
                        val time = if (s.day == next.day) clockLabel(s.listedMs) else "${weekdayLabel(s.day)} ${clockLabel(s.listedMs)}"
                        MiniRow(time, s.title, s.kind, strong = i == 0, lines = b.lines(s.title, 12f, rowWidth - if (s.day != next.day) 26f else 0f, max = 2))
                        Spacer(GlanceModifier.height(4.dp))
                    }
                }
            }
        }
    }
}
