package dev.southstar21.vectra.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import dev.southstar21.vectra.features.widgets.NextUp
import dev.southstar21.vectra.features.widgets.WidgetSession
import dev.southstar21.vectra.features.widgets.clockLabel
import dev.southstar21.vectra.features.widgets.countdownLabel
import dev.southstar21.vectra.features.widgets.dayDistanceLabel
import dev.southstar21.vectra.features.widgets.nextUp
import dev.southstar21.vectra.features.widgets.progress

/**
 * Lock screen: the iOS rectangular accessory for lock screens that host app widgets.
 * No surface, white type over the wallpaper, so the design lives in the type.
 */
class LockScreenWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val frame = WidgetStore.frame(context)
        provideContent { LockScreenContent(context, frame) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { LockScreenContent(context, sampleFrame()) }
    }
}

// Fixed white: a lock screen sits on the wallpaper, not the light/dark canvas.
private val Primary = ColorProvider(Color.White)
private val Dim = ColorProvider(Color(0xCCFFFFFF))
private val Track = ColorProvider(Color(0x40FFFFFF))

@Composable
private fun LockScreenContent(context: Context, frame: WidgetFrame) {
    Column(
        GlanceModifier.fillMaxSize().opensSchedule(context),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (val up = frame.snapshot.nextUp(frame.nowMs, frame.today)) {
            is NextUp.InClass -> {
                val s = up.session
                Detail(listOf("Now", s.location).filter { it.isNotBlank() }.joinToString(" · "))
                Headline(s.title)
                Spacer(GlanceModifier.height(5.dp))
                LinearProgressIndicator(
                    progress = progress(s, frame.nowMs),
                    modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                    color = Primary,
                    backgroundColor = Track,
                )
            }
            is NextUp.Upcoming -> Coming(up.session, frame)
            is NextUp.ExamSoon -> Coming(up.exam, frame)
            is NextUp.DayDone -> {
                Detail(if (up.hadToday) "Done for today" else "No classes today")
                up.next?.let {
                    Headline(it.title)
                    Clock("${dayDistanceLabel(frame.today, it.day)} ${clockLabel(it.startMs)}")
                }
            }
            NextUp.NoData -> {
                Headline("Vectra")
                Detail("Sign in to see your classes")
            }
        }
    }
}

@Composable
private fun Coming(s: WidgetSession, frame: WidgetFrame) {
    Detail(listOf(s.kind.label, s.location).filter { it.isNotBlank() }.joinToString(" · "))
    Headline(s.title)
    Clock(
        if (s.day == frame.today) "${clockLabel(s.startMs)} · ${countdownLabel(frame.nowMs, s.startMs)}"
        else "${dayDistanceLabel(frame.today, s.day)} ${clockLabel(s.startMs)}",
    )
}

@Composable
private fun Detail(text: String) = Text(text, style = style(13.sp, FontWeight.Medium, Dim), maxLines = 1)

@Composable
private fun Headline(text: String) = Text(text, style = style(17.sp, FontWeight.Bold, Primary), maxLines = 1)

@Composable
private fun Clock(text: String) = Text(text, style = style(13.sp, FontWeight.Bold, Primary, mono = true), maxLines = 1)
