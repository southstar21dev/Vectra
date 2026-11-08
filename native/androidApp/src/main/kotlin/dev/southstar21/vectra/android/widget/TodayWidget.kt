package dev.southstar21.vectra.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import dev.southstar21.vectra.android.R
import dev.southstar21.vectra.features.widgets.NextUp
import dev.southstar21.vectra.features.widgets.WidgetSession
import dev.southstar21.vectra.features.widgets.clockLabel
import dev.southstar21.vectra.features.widgets.countdownLabel
import dev.southstar21.vectra.features.widgets.dayDistanceLabel
import dev.southstar21.vectra.features.widgets.featured
import dev.southstar21.vectra.features.widgets.laterOn
import dev.southstar21.vectra.features.widgets.nextUp
import dev.southstar21.vectra.features.widgets.progress
import dev.southstar21.vectra.features.widgets.remainingLabel
import dev.southstar21.vectra.features.widgets.weekdayLabel

/**
 * Medium: the class now (or next) as a card, and what's left of the day beside it. Once the day
 * is done it looks ahead to the next day with classes.
 */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val frame = WidgetStore.frame(context)
        provideContent { TodayContent(context, frame) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { TodayContent(context, sampleFrame()) }
    }
}

@Composable
private fun TodayContent(context: Context, frame: WidgetFrame) {
    val up = frame.snapshot.nextUp(frame.nowMs, frame.today)
    if (up == NextUp.NoData) {
        EmptyState(context, "Open Vectra and sign in to see your day here.")
        return
    }
    val featured = up.featured
    val day = featured?.day ?: frame.today
    val later = frame.snapshot.laterOn(day, frame.nowMs, featured).take(3)
    val heading = if (day == frame.today) "LATER TODAY" else "ALSO ${dayDistanceLabel(frame.today, day).uppercase()}"
    WidgetSurface(context, padding = 12.dp) {
        Row(GlanceModifier.fillMaxSize()) {
            FeaturedCard(featured, up is NextUp.InClass, frame)
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
                Text(heading, style = style(Micro, FontWeight.Bold, WColors.muted), maxLines = 1)
                Spacer(GlanceModifier.height(6.dp))
                if (later.isEmpty()) {
                    Text(if (day == frame.today) "Nothing else today." else "Nothing else that day.", style = style(Caption, FontWeight.Medium, WColors.secondary))
                }
                later.forEach { LaterRow(it) }
            }
        }
    }
}

@Composable
private fun FeaturedCard(s: WidgetSession?, live: Boolean, frame: WidgetFrame) {
    val nowMs = frame.nowMs
    Column(
        GlanceModifier
            .width(150.dp)
            .fillMaxHeight()
            .background(ImageProvider(if (live) R.drawable.widget_live else R.drawable.widget_card))
            .padding(12.dp),
    ) {
        if (s == null) {
            Tinted(R.drawable.ic_widget_done, WColors.emerald, 20.dp)
            Spacer(GlanceModifier.defaultWeight())
            Text("Nothing coming up", style = style(TitleSize, FontWeight.Bold), maxLines = 2)
            return@Column
        }
        val later = s.day != frame.today
        StatusLabel(
            when {
                live -> "Now"
                later -> "${dayDistanceLabel(frame.today, s.day)} · ${s.kind.label}"
                else -> s.kind.label
            },
            s.kind,
        )
        Spacer(GlanceModifier.defaultWeight())
        Text(s.title, style = style(TitleSize, FontWeight.Bold), maxLines = 2)
        Spacer(GlanceModifier.height(8.dp))
        if (live) {
            Progress(progress(s, nowMs))
            Spacer(GlanceModifier.height(6.dp))
            Text(remainingLabel(nowMs, s.endMs), style = style(Caption, FontWeight.Medium, WColors.secondary), maxLines = 1)
        } else if (later) {
            AccentPill("${weekdayLabel(s.day)} ${clockLabel(s.startMs)}")
        } else {
            AccentPill(countdownLabel(nowMs, s.startMs))
        }
    }
}

@Composable
private fun LaterRow(s: WidgetSession) {
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(clockLabel(s.startMs), style = style(Caption, FontWeight.Bold, WColors.muted, mono = true), modifier = GlanceModifier.width(44.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(s.title, style = style(Caption, FontWeight.Bold), maxLines = 1)
            StatusLabel(listOf(s.kind.label, s.location).filter { it.isNotBlank() }.joinToString(" · "), s.kind)
        }
    }
}
