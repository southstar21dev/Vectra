package dev.southstar21.vectra.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import dev.southstar21.vectra.android.R
import dev.southstar21.vectra.features.widgets.WidgetKind
import dev.southstar21.vectra.features.widgets.WidgetSession
import dev.southstar21.vectra.features.widgets.breakMinutes
import dev.southstar21.vectra.features.widgets.clockLabel
import dev.southstar21.vectra.features.widgets.dayDistanceLabel
import dev.southstar21.vectra.features.widgets.dayHeading
import dev.southstar21.vectra.features.widgets.isClass
import dev.southstar21.vectra.features.widgets.progress
import dev.southstar21.vectra.features.widgets.sessionsOn

/** Large: today as the Schedule tab shows it, glass cards with stacked times. */
class AgendaWidget : GlanceAppWidget() {
    // Exact: the rows are fitted to the size the launcher actually gives (cells differ a lot).
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val frame = WidgetStore.frame(context)
        provideContent { AgendaContent(context, frame) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { AgendaContent(context, sampleFrame()) }
    }
}

/** At most this many cards; fewer when the widget is short. The rest is summed up in the footer. */
private const val MaxCards = 4

/** Rough heights, for fitting rows to the widget: header and footer, a card, a break capsule. */
private val ChromeHeight = 118.dp
private val CardHeight = 64.dp
private val BreakHeight = 32.dp

private sealed interface AgendaRow {
    data class Card(val session: WidgetSession) : AgendaRow
    data class Break(val minutes: Int) : AgendaRow
}

@Composable
private fun AgendaContent(context: Context, frame: WidgetFrame) {
    val snapshot = frame.snapshot
    if (snapshot == null || !snapshot.signedIn) {
        EmptyState(context, "Open Vectra and sign in to see today here.")
        return
    }
    // A day with nothing on (a weekend, a holiday) shows the next day that has something instead.
    val shownDay = if (snapshot.sessionsOn(frame.today).isNotEmpty()) frame.today
    else snapshot.sessions.firstOrNull { it.day > frame.today }?.day ?: frame.today
    val today = snapshot.sessionsOn(shownDay)
    // Keep the end of the day in view: drop finished sessions first when there are too many.
    val finished = today.count { it.endMs <= frame.nowMs }
    val room = LocalSize.current.height - ChromeHeight
    val cards = (room.value / CardHeight.value).toInt().coerceIn(1, MaxCards)
    val visible = today.drop((today.size - cards).coerceIn(0, finished)).take(cards)
    val breaks = ((room.value - visible.size * CardHeight.value) / BreakHeight.value).toInt().coerceAtLeast(0)
    val rest = today.drop(today.indexOf(visible.lastOrNull()) + 1)
    val tomorrow = snapshot.sessions.firstOrNull { it.day > shownDay && it.kind.isTimed }
    val footer = when {
        rest.isNotEmpty() -> "Then: ${rest.first().title}" to clockLabel(rest.first().startMs)
        tomorrow != null -> "${dayDistanceLabel(frame.today, tomorrow.day)}: ${tomorrow.title}" to clockLabel(tomorrow.startMs)
        else -> null
    }

    WidgetSurface(context, padding = 0.dp) {
        Column(GlanceModifier.fillMaxSize()) {
            Column(GlanceModifier.fillMaxWidth().defaultWeight().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
                AgendaHeader(frame.today, shownDay, today)
                Spacer(GlanceModifier.height(8.dp))
                if (today.isEmpty()) {
                    Text("Nothing on today.", style = style(Caption, FontWeight.Medium, WColors.secondary), modifier = GlanceModifier.padding(horizontal = 4.dp))
                }
                // Glance drops a column's children past the tenth, so the rows get a column of their own
                // and each row carries its gap.
                Column(GlanceModifier.fillMaxWidth()) {
                    agendaRows(visible, breaks).forEach { row ->
                        Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            when (row) {
                                is AgendaRow.Card -> AgendaCard(row.session, frame.nowMs)
                                is AgendaRow.Break -> BreakCapsule(row.minutes)
                            }
                        }
                    }
                }
            }
            if (footer != null) AgendaFooter(footer.first, footer.second)
        }
    }
}

/** The cards with a break capsule wherever there's real time between two; past [maxBreaks] the shortest go. */
private fun agendaRows(visible: List<WidgetSession>, maxBreaks: Int): List<AgendaRow> {
    val rows = buildList {
        visible.forEachIndexed { i, s ->
            if (i > 0) breakMinutes(visible[i - 1], s)?.let { add(AgendaRow.Break(it)) }
            add(AgendaRow.Card(s))
        }
    }.toMutableList()
    while (rows.count { it is AgendaRow.Break } > maxBreaks) {
        rows.remove(rows.filterIsInstance<AgendaRow.Break>().minByOrNull { it.minutes } ?: break)
    }
    return rows
}

@Composable
private fun AgendaHeader(today: String, day: String, sessions: List<WidgetSession>) {
    Row(GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
            Text(dayDistanceLabel(today, day), style = style(18.sp, FontWeight.Bold))
            Text(dayHeading(day), style = style(Caption, FontWeight.Medium, WColors.muted))
        }
        val classes = sessions.count { it.kind.isClass || it.kind == WidgetKind.Exam }
        if (classes > 0) StatusLabel(if (classes == 1) "1 class" else "$classes classes", WidgetKind.Lecture)
    }
}

/** The card footer bar: a hairline over a faint tint. */
@Composable
private fun AgendaFooter(text: String, time: String) {
    Column(GlanceModifier.fillMaxWidth()) {
        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(WColors.hairline)) {}
        Row(
            GlanceModifier.fillMaxWidth().background(WColors.overlay).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = style(Caption, FontWeight.Medium, WColors.secondary), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(8.dp))
            Text(time, style = style(Caption, FontWeight.Medium, WColors.muted, mono = true))
        }
    }
}

@Composable
private fun AgendaCard(s: WidgetSession, nowMs: Long) {
    val live = s.startMs <= nowMs && nowMs < s.endMs && s.kind.isTimed
    val done = s.endMs <= nowMs
    Row(
        GlanceModifier
            .fillMaxWidth()
            .background(ImageProvider(if (live) R.drawable.widget_live else R.drawable.widget_card))
            .padding(start = 12.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(GlanceModifier.defaultWeight()) {
            Text(s.title, style = style(14.sp, FontWeight.Bold, if (done) WColors.muted else WColors.text), maxLines = 1)
            Spacer(GlanceModifier.height(2.dp))
            if (done) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tinted(R.drawable.ic_widget_done, WColors.emerald, 14.dp)
                    Spacer(GlanceModifier.width(6.dp))
                    Text(listOf("Done", s.location).filter { it.isNotBlank() }.joinToString(" · "), style = style(Caption, FontWeight.Medium, WColors.muted), maxLines = 1)
                }
            } else {
                StatusLabel(listOf(s.kind.label, s.location).filter { it.isNotBlank() }.joinToString(" · "), s.kind)
            }
            if (live) {
                Spacer(GlanceModifier.height(5.dp))
                Progress(progress(s, nowMs), height = 4.dp)
            }
        }
        Spacer(GlanceModifier.width(8.dp))
        StackedTime(clockLabel(s.startMs), if (s.kind.isTimed) clockLabel(s.endMs) else null)
    }
}

@Composable
private fun BreakCapsule(minutes: Int) {
    Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(GlanceModifier.background(ImageProvider(R.drawable.widget_chip)).padding(horizontal = 12.dp, vertical = 3.dp)) {
            val label = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min".replace(" 0 min", "") else "$minutes min"
            Text("Break · $label", style = style(Caption, FontWeight.Medium, WColors.muted))
        }
    }
}
