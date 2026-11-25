package dev.southstar21.vectra.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
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
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.features.widgets.WidgetKind
import dev.southstar21.vectra.features.widgets.WidgetSession
import dev.southstar21.vectra.features.widgets.clockLabel
import dev.southstar21.vectra.features.widgets.dayDistanceLabel
import dev.southstar21.vectra.features.widgets.dayHeading
import dev.southstar21.vectra.features.widgets.emptyMessage
import dev.southstar21.vectra.features.widgets.listedMs
import dev.southstar21.vectra.features.widgets.progress
import dev.southstar21.vectra.features.widgets.remainingLabel
import dev.southstar21.vectra.features.widgets.sessionsOn

/** Medium: what's left today on a time line, then the next day under its name. */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetTheme { TodayContent(context, rememberFrame(context)) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { PreviewTheme(360.dp, 170.dp) { TodayContent(context, sampleFrame()) } }
    }
}

private sealed interface Item {
    class Session(val s: WidgetSession) : Item

    class Day(val label: String) : Item
}

@Composable
private fun TodayContent(context: Context, frame: WidgetFrame) {
    val snap = frame.snapshot
    if (snap?.signedIn != true) {
        EmptyState(context, snap.emptyMessage("Open Vectra and sign in to see your day here."))
        return
    }
    val items = buildList<Item> {
        addAll(snap.sessionsOn(frame.today).filter { it.endMs > frame.nowMs }.map { Item.Session(it) })
        // Then the days after, each under its name, until the widget is full.
        snap.sessions.map { it.day }.filter { it > frame.today }.distinct().forEach { day ->
            if (size >= 8) return@forEach
            add(Item.Day("${dayDistanceLabel(frame.today, day)}, ${dayHeading(day)}"))
            addAll(snap.sessionsOn(day).map { Item.Session(it) })
        }
    }
    val size = LocalSize.current
    val b = Budget(context)
    val nameWidth = size.width.value - 27f - 40f - 9f - 11f - 9f
    val heights = items.map { item ->
        when (item) {
            is Item.Day -> b.line(10f) + 2f
            is Item.Session -> rowHeight(b, item.s, frame.nowMs, nameWidth)
        }
    }
    var shown = items.take(fitRows(heights.map { it + 5f }, size.height.value - 21f).coerceAtMost(9))
    while (shown.lastOrNull() is Item.Day) shown = shown.dropLast(1)
    WidgetSurface(context, padding = 0.dp) {
        Column(GlanceModifier.fillMaxSize().padding(start = 12.dp, top = 11.dp, end = 15.dp, bottom = 10.dp)) {
            if (shown.isEmpty()) Quiet("Nothing coming up in the next two weeks.", 12f)
            shown.forEachIndexed { i, item ->
                when (item) {
                    // One child per item: Glance draws at most ten per column.
                    is Item.Day -> Column {
                        Row(GlanceModifier.padding(start = 60.dp, top = if (i == 0) 0.dp else 2.dp)) { Micro(item.label) }
                        Spacer(GlanceModifier.height(5.dp))
                    }
                    is Item.Session -> {
                        val last = i == shown.lastIndex || shown[i + 1] is Item.Day
                        // The gap below belongs to the row, so the time line runs on through it.
                        CompactRow(item.s, frame.nowMs, heights[i] + 5f, last, b.lines(item.s.title, 14f, nameWidth, max = 2))
                    }
                }
            }
        }
    }
}

private fun rowHeight(b: Budget, s: WidgetSession, nowMs: Long, nameWidth: Float): Float {
    val live = s.startMs <= nowMs && nowMs < s.endMs && s.kind.isTimed
    val body = b.line(10f) + 1f + b.height(s.title, 14f, nameWidth, max = 2) + if (live) 7f else 0f
    val times = b.line(12f) + if (s.kind.isTimed) b.line(10f) + 1f else 0f
    return maxOf(body, times)
}

/** One row: start over end, a node on the line, the type and room over the name. */
@Composable
private fun CompactRow(s: WidgetSession, nowMs: Long, height: Float, last: Boolean, nameLines: Int) {
    val p = LocalWPalette.current
    val live = s.kind.isTimed && s.startMs <= nowMs && nowMs < s.endMs
    Row(GlanceModifier.fillMaxWidth().height(height.dp)) {
        Column(GlanceModifier.width(40.dp), horizontalAlignment = Alignment.End) {
            Mono(clockLabel(s.listedMs))
            if (s.kind.isTimed) Mono(clockLabel(s.endMs), 10f, p.muted, FontWeight.Medium)
        }
        Spacer(GlanceModifier.width(9.dp))
        Column(GlanceModifier.width(11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(GlanceModifier.height(2.dp))
            Node(s.kind, live, done = false)
            if (!last) {
                Spacer(GlanceModifier.height(1.dp))
                SpineLine((height - 15f).dp)
            }
        }
        Spacer(GlanceModifier.width(9.dp))
        Column(GlanceModifier.defaultWeight()) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TypeWord(s.kind)
                if (live) {
                    Spacer(GlanceModifier.width(6.dp))
                    Text(remainingLabel(nowMs, s.endMs), style = style(10.sp, FontWeight.Bold, p.tone(s.kind)), maxLines = 1)
                }
                Spacer(GlanceModifier.defaultWeight())
                if (s.kind == WidgetKind.Task) Quiet("Due") else if (s.location.isNotBlank()) RoomLine(s.location)
            }
            Name(s.title, 14f, maxLines = nameLines)
            if (live) {
                Spacer(GlanceModifier.height(4.dp))
                Bar(progress(s, nowMs), s.kind)
            }
        }
    }
}
