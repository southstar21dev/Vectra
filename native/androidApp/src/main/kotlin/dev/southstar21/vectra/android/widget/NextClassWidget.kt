package dev.southstar21.vectra.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
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
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import dev.southstar21.vectra.android.R
import dev.southstar21.vectra.features.widgets.NextUp
import dev.southstar21.vectra.features.widgets.WidgetKind
import dev.southstar21.vectra.features.widgets.WidgetSession
import dev.southstar21.vectra.features.widgets.clockLabel
import dev.southstar21.vectra.features.widgets.countdownLabel
import dev.southstar21.vectra.features.widgets.dayDistanceLabel
import dev.southstar21.vectra.features.widgets.nextUp
import dev.southstar21.vectra.features.widgets.progress
import dev.southstar21.vectra.features.widgets.remainingLabel
import dev.southstar21.vectra.features.widgets.weekdayLabel

/** Small: the class you're in, or the next one, with a countdown. */
class NextClassWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val frame = WidgetStore.frame(context)
        provideContent { NextClassContent(context, frame) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { NextClassContent(context, sampleFrame()) }
    }
}

@Composable
private fun NextClassContent(context: Context, frame: WidgetFrame) {
    when (val up = frame.snapshot.nextUp(frame.nowMs, frame.today)) {
        NextUp.NoData -> EmptyState(context, "Open Vectra and sign in to see your classes here.")
        is NextUp.InClass -> InClass(context, up.session, frame.nowMs)
        is NextUp.Upcoming -> Upcoming(context, up.session, frame.nowMs)
        is NextUp.ExamSoon -> ExamSoon(context, up.exam, frame.today)
        is NextUp.DayDone -> DayDone(context, up, frame.today)
    }
}

@Composable
private fun Upcoming(context: Context, s: WidgetSession, nowMs: Long) {
    WidgetSurface(context) {
        Column(GlanceModifier.fillMaxSize()) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.defaultWeight()) { StatusLabel(s.kind.label, s.kind) }
                Text(clockLabel(s.startMs), style = style(Caption, FontWeight.Medium, WColors.muted, mono = true))
            }
            Spacer(GlanceModifier.defaultWeight())
            Text(s.title, style = style(TitleSize, FontWeight.Bold), maxLines = 2)
            Spacer(GlanceModifier.height(8.dp))
            AccentPill(countdownLabel(nowMs, s.startMs))
            Spacer(GlanceModifier.defaultWeight())
            Place(s.location)
        }
    }
}

@Composable
private fun InClass(context: Context, s: WidgetSession, nowMs: Long) {
    WidgetSurface(context, live = true) {
        Column(GlanceModifier.fillMaxSize()) {
            StatusLabel("Now · ${s.kind.label}", s.kind)
            Spacer(GlanceModifier.defaultWeight())
            Text(s.title, style = style(TitleSize, FontWeight.Bold), maxLines = 2)
            Spacer(GlanceModifier.height(10.dp))
            Progress(progress(s, nowMs))
            Spacer(GlanceModifier.height(6.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                Text(remainingLabel(nowMs, s.endMs), style = style(Caption, FontWeight.Medium, WColors.secondary), modifier = GlanceModifier.defaultWeight(), maxLines = 1)
                Text(clockLabel(s.endMs), style = style(Caption, FontWeight.Medium, WColors.muted, mono = true))
            }
            Spacer(GlanceModifier.defaultWeight())
            Place(s.location)
        }
    }
}

@Composable
private fun ExamSoon(context: Context, exam: WidgetSession, today: String) {
    WidgetSurface(context) {
        Column(GlanceModifier.fillMaxSize()) {
            StatusLabel("Exam", WidgetKind.Exam)
            Spacer(GlanceModifier.defaultWeight())
            Text(dayDistanceLabel(today, exam.day), style = style(24.sp, FontWeight.Bold, WColors.violet), maxLines = 1)
            Text(exam.title, style = style(TitleSize, FontWeight.Bold), maxLines = 2)
            Spacer(GlanceModifier.defaultWeight())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tinted(R.drawable.ic_widget_calendar, WColors.secondary, 14.dp)
                Spacer(GlanceModifier.width(6.dp))
                val where = listOf("${weekdayLabel(exam.day)} ${clockLabel(exam.startMs)}", exam.location).filter { it.isNotBlank() }
                Text(where.joinToString(" · "), style = style(Caption, FontWeight.Medium, WColors.secondary), maxLines = 1)
            }
        }
    }
}

@Composable
private fun DayDone(context: Context, up: NextUp.DayDone, today: String) {
    WidgetSurface(context) {
        Column(GlanceModifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tinted(R.drawable.ic_widget_done, WColors.emerald, 22.dp)
                Spacer(GlanceModifier.width(8.dp))
                Text(if (up.hadToday) "Done for today" else "No classes today", style = style(TitleSize, FontWeight.Bold), maxLines = 2)
            }
            Spacer(GlanceModifier.defaultWeight())
            val next = up.next
            if (next != null) {
                Text(dayDistanceLabel(today, next.day).uppercase(), style = style(Micro, FontWeight.Bold, WColors.muted))
                Spacer(GlanceModifier.height(6.dp))
                Column(
                    GlanceModifier.fillMaxWidth().background(ImageProvider(R.drawable.widget_card)).padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(next.title, style = style(Caption, FontWeight.Bold), maxLines = 1)
                    val where = listOf(clockLabel(next.startMs), next.location).filter { it.isNotBlank() }
                    Text(where.joinToString(" · "), style = style(Caption, FontWeight.Medium, WColors.muted), maxLines = 1)
                }
            } else {
                Text("Nothing coming up in the next two weeks.", style = style(Caption, FontWeight.Medium, WColors.secondary), maxLines = 3)
            }
        }
    }
}
