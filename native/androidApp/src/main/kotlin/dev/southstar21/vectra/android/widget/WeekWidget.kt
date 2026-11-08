package dev.southstar21.vectra.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
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
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import dev.southstar21.vectra.android.R
import dev.southstar21.vectra.features.widgets.WeekDay
import dev.southstar21.vectra.features.widgets.WidgetKind
import dev.southstar21.vectra.features.widgets.classCount
import dev.southstar21.vectra.features.widgets.clockLabel
import dev.southstar21.vectra.features.widgets.examThisWeek
import dev.southstar21.vectra.features.widgets.showsNextWeek
import dev.southstar21.vectra.features.widgets.week
import dev.southstar21.vectra.features.widgets.weekdayLabel

/** Medium: the week at a glance, one coloured bar per class. */
class WeekWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val frame = WidgetStore.frame(context)
        provideContent { WeekContent(context, frame) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { WeekContent(context, sampleFrame()) }
    }
}

private const val MaxBars = 4

@Composable
private fun WeekContent(context: Context, frame: WidgetFrame) {
    val snapshot = frame.snapshot
    if (snapshot == null || !snapshot.signedIn) {
        EmptyState(context, "Open Vectra and sign in to see your week here.")
        return
    }
    val days = snapshot.week(frame.today)
    val exam = snapshot.examThisWeek(frame.today)
    WidgetSurface(context, padding = 0.dp) {
        Column(GlanceModifier.fillMaxSize()) {
            Column(GlanceModifier.fillMaxWidth().defaultWeight().padding(start = 14.dp, end = 14.dp, top = 12.dp)) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val title = if (showsNextWeek(frame.today)) "Next week" else "This week"
                    Text(title, style = style(18.sp, FontWeight.Bold), modifier = GlanceModifier.defaultWeight())
                }
                Spacer(GlanceModifier.height(8.dp))
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    days.forEach { DayColumn(it) }
                }
            }
            Box(GlanceModifier.fillMaxWidth().height(1.dp).background(WColors.hairline)) {}
            Row(
                GlanceModifier.fillMaxWidth().background(WColors.overlay).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val count = days.classCount()
                Text(
                    if (count == 1) "1 class" else "$count classes",
                    style = style(Caption, FontWeight.Medium, WColors.secondary),
                    modifier = GlanceModifier.defaultWeight(),
                )
                if (exam != null) StatusLabel("Exam ${weekdayLabel(exam.day)} ${clockLabel(exam.startMs)}", WidgetKind.Exam)
            }
        }
    }
}

@Composable
private fun RowScope.DayColumn(day: WeekDay) {
    Column(
        GlanceModifier.defaultWeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val past = day.isPast && !day.isToday
        Text(day.weekday.uppercase(), style = style(Micro, FontWeight.Bold, if (day.isToday) WColors.accent else WColors.muted))
        Spacer(GlanceModifier.height(4.dp))
        Box(
            GlanceModifier.size(30.dp).then(
                if (day.isToday) GlanceModifier.background(ImageProvider(R.drawable.widget_pill)) else GlanceModifier,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                style = style(14.sp, FontWeight.Bold, if (day.isToday) WColors.onAccent else if (past) WColors.muted else WColors.text),
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        if (day.kinds.isEmpty()) {
            Text("Free", style = style(Caption, FontWeight.Medium, WColors.muted))
        }
        day.kinds.take(MaxBars).forEach { kind ->
            Box(
                GlanceModifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Box(GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp).background(if (past) WColors.track else kind.tone())) {}
            }
        }
        if (day.kinds.size > MaxBars) {
            Text("+${day.kinds.size - MaxBars}", style = style(Micro, FontWeight.Bold, WColors.muted))
        }
    }
}
