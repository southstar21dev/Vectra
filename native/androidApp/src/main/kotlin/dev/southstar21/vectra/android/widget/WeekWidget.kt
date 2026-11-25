package dev.southstar21.vectra.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.features.widgets.WeekBlock
import dev.southstar21.vectra.features.widgets.WeekColumn
import dev.southstar21.vectra.features.widgets.WeekGrid
import dev.southstar21.vectra.features.widgets.WidgetKind
import dev.southstar21.vectra.features.widgets.classCount
import dev.southstar21.vectra.features.widgets.clockLabel
import dev.southstar21.vectra.features.widgets.emptyMessage
import dev.southstar21.vectra.features.widgets.examThisWeek
import dev.southstar21.vectra.features.widgets.week
import dev.southstar21.vectra.features.widgets.weekGrid
import dev.southstar21.vectra.features.widgets.weekdayLabel
import dev.southstar21.vectra.features.widgets.weekendFree
import kotlin.time.Instant

/** Medium: the week as a small timetable, with the next exam. */
class WeekWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetTheme { WeekContent(context, rememberFrame(context)) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { PreviewTheme(360.dp, 170.dp) { WeekContent(context, sampleFrame()) } }
    }
}

private fun minuteOfDay(ms: Long): Int = nowInBudapest(Instant.fromEpochMilliseconds(ms)).let { it.hours * 60 + it.minutes }

@Composable
private fun WeekContent(context: Context, frame: WidgetFrame) {
    val snap = frame.snapshot
    if (snap?.signedIn != true) {
        EmptyState(context, snap.emptyMessage("Open Vectra and sign in to see your week here."))
        return
    }
    val p = LocalWPalette.current
    val grid = snap.weekGrid(frame.today, frame.nowMs, ::minuteOfDay)
    val classes = snap.week(frame.today).classCount()
    val exam = snap.examThisWeek(frame.today)
    val b = budgetFor(context)
    val countText = (if (classes == 1) "1 class" else "$classes classes") + if (grid.weekendFree()) ", weekend free" else ""
    val examText = exam?.let { "${it.title}, ${weekdayLabel(it.day)} " }.orEmpty()
    // The exam title takes what the class count and the time leave, on up to two lines, never cutting the time.
    val examRoom = LocalSize.current.width.value - 23f - 11f * 0.5f * countText.length - 60f
    val examLines = if (exam == null) 1 else b.lines(examText, 11f, examRoom.coerceAtLeast(60f), bold = false, max = 2)
    val height = (LocalSize.current.height.value - 21f - 24f - b.line(11f) * examLines - 7f).coerceAtLeast(40f)
    WidgetSurface(context, padding = 0.dp) {
        Column(GlanceModifier.fillMaxSize().padding(start = 10.dp, top = 11.dp, end = 13.dp, bottom = 10.dp)) {
            Row(GlanceModifier.fillMaxWidth()) {
                HourLabels(grid, height.dp)
                grid.columns.forEach { DayColumn(it, grid, height.dp, frame.nowMs) }
            }
            Spacer(GlanceModifier.height(7.dp))
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Quiet(countText)
                Spacer(GlanceModifier.width(8.dp))
                if (exam != null) {
                    TypeWord(WidgetKind.Exam)
                    Spacer(GlanceModifier.width(6.dp))
                    Box(GlanceModifier.defaultWeight()) { Quiet(examText, maxLines = 2) }
                    Mono(clockLabel(exam.startMs), 11f, p.secondary)
                } else {
                    Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

private fun y(minute: Int, grid: WeekGrid, height: Dp): Dp =
    height * ((minute - grid.fromHour * 60).toFloat() / ((grid.toHour - grid.fromHour) * 60))

private fun hours(grid: WeekGrid): List<Int> = (grid.fromHour..grid.toHour step if (grid.toHour - grid.fromHour > 8) 2 else 1).toList()

@Composable
private fun HourLabels(grid: WeekGrid, height: Dp) {
    val p = LocalWPalette.current
    Column(GlanceModifier.width(16.dp).padding(top = 18.dp)) {
        var cursor = 0.dp
        hours(grid).take(10).forEach { h ->
            val at = (y(h * 60, grid, height) - 6.dp).coerceAtLeast(cursor)
            // Each hour one child: Glance draws at most ten per column.
            Column {
                Spacer(GlanceModifier.height(at - cursor))
                Text(h.toString().padStart(2, '0'), style = style(9.sp, FontWeight.Bold, p.muted, mono = true), maxLines = 1)
            }
            cursor = at + 12.dp
        }
    }
}

@Composable
private fun RowScope.DayColumn(column: WeekColumn, grid: WeekGrid, height: Dp, nowMs: Long) {
    val p = LocalWPalette.current
    val day = column.day
    Column(GlanceModifier.defaultWeight().padding(horizontal = 3.dp)) {
        Box(
            GlanceModifier.fillMaxWidth().height(19.dp).cornerRadius(10.dp).then(if (day.isToday) GlanceModifier.background(p.pill) else GlanceModifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${weekdayLabel(day.date)} ${day.dayOfMonth}",
                style = style(11.sp, FontWeight.Bold, if (day.isToday) p.onPill else if (day.isPast) p.muted else p.text).copy(textAlign = TextAlign.Center),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.height(5.dp))
        Box(GlanceModifier.fillMaxWidth().height(height)) {
            GridLines(grid, height)
            Blocks(column.blocks, grid, height)
            if (day.isToday) NowTick(minuteOfDay(nowMs), grid, height)
        }
    }
}

@Composable
private fun GridLines(grid: WeekGrid, height: Dp) {
    Column(GlanceModifier.fillMaxWidth()) {
        var cursor = 0.dp
        hours(grid).take(10).forEach { h ->
            val at = y(h * 60, grid, height).coerceAtMost(height - 1.dp)
            Column(GlanceModifier.fillMaxWidth()) {
                Spacer(GlanceModifier.height((at - cursor).coerceAtLeast(0.dp)))
                Hair()
            }
            cursor = at + 1.dp
        }
    }
}

/** The blocks top to bottom; a clash starts where the class before it ends. */
@Composable
private fun Blocks(blocks: List<WeekBlock>, grid: WeekGrid, height: Dp) {
    val p = LocalWPalette.current
    Column(GlanceModifier.fillMaxWidth()) {
        var cursor = 0.dp
        blocks.sortedBy { it.startMin }.take(10).forEach { b ->
            val top = y(b.startMin, grid, height).coerceAtLeast(cursor)
            val bottom = y(b.endMin, grid, height).coerceAtMost(height)
            val h = bottom - top - 1.5.dp
            if (h < 3.dp) return@forEach
            Column(GlanceModifier.fillMaxWidth()) {
                Spacer(GlanceModifier.height(top - cursor + 0.5.dp))
                Box(
                    GlanceModifier.fillMaxWidth().height(h).cornerRadius(4.dp).background(if (b.done) p.track else p.tone(b.kind)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (h >= 11.dp) {
                        Text(b.label, GlanceModifier.padding(start = 6.dp), style(8.sp, FontWeight.Bold, if (b.done) p.muted else p.onPill, mono = true), maxLines = 1)
                    }
                }
            }
            cursor = top + 0.5.dp + h
        }
    }
}

@Composable
private fun NowTick(minute: Int, grid: WeekGrid, height: Dp) {
    val p = LocalWPalette.current
    val at = y(minute, grid, height)
    if (at < 0.dp || at > height) return
    Column {
        Spacer(GlanceModifier.height((at - 3.dp).coerceAtLeast(0.dp)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(GlanceModifier.width(6.dp).height(6.dp).cornerRadius(3.dp).background(p.text)) {}
            Box(GlanceModifier.width(6.dp).height(2.dp).background(p.text)) {}
        }
    }
}
