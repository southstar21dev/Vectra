package dev.southstar21.vectra.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import dev.southstar21.vectra.android.R
import dev.southstar21.vectra.features.widgets.WidgetKind

// The time-spine pieces every widget is built from, after the app's own.


/** The class type as the app writes it: an uppercase word in the type's colour. */
@Composable
internal fun TypeWord(kind: WidgetKind) {
    val p = LocalWPalette.current
    Text(kind.label.uppercase(), style = style(10.sp, FontWeight.Bold, p.tone(kind), mono = true), maxLines = 1)
}

/** A name; long ones wrap rather than get cut off (the budget leaves them the room). */
@Composable
internal fun Name(text: String, size: Float = 15f, maxLines: Int = 3, dim: Boolean = false) {
    val p = LocalWPalette.current
    Text(text, style = style(size.sp, FontWeight.Bold, if (dim) p.muted else p.text), maxLines = maxLines)
}

@Composable
internal fun Quiet(text: String, size: Float = 11f, color: ColorProvider? = null, maxLines: Int = 1) {
    val p = LocalWPalette.current
    Text(text, style = style(size.sp, FontWeight.Medium, color ?: p.secondary), maxLines = maxLines)
}

/** A time. In the system sans, whose digits are already all one width (its monospace spaces out the colon). */
@Composable
internal fun Mono(text: String, size: Float = 12f, color: ColorProvider? = null, weight: FontWeight = FontWeight.Bold) {
    val p = LocalWPalette.current
    Text(text, style = style(size.sp, weight, color ?: p.text), maxLines = 1)
}

@Composable
internal fun Micro(text: String) {
    val p = LocalWPalette.current
    Text(text.uppercase(), style = style(10.sp, FontWeight.Bold, p.muted), maxLines = 1)
}

/** Pin and room, people and group. */
@Composable
internal fun RoomLine(room: String, group: String = "") {
    val p = LocalWPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (room.isNotBlank()) {
            Tinted(R.drawable.ic_widget_pin, p.secondary, 12.dp)
            Spacer(GlanceModifier.width(4.dp))
            Quiet(room)
        }
        if (group.isNotBlank()) {
            if (room.isNotBlank()) Spacer(GlanceModifier.width(8.dp))
            Tinted(R.drawable.ic_widget_group, p.secondary, 12.dp)
            Spacer(GlanceModifier.width(4.dp))
            Quiet(group)
        }
    }
}

@Composable
internal fun Hair() {
    val p = LocalWPalette.current
    Box(GlanceModifier.fillMaxWidth().height(1.dp).background(p.hairline)) {}
}

/** A flat progress line in the type's colour. */
@Composable
internal fun Bar(fraction: Float, kind: WidgetKind, height: Dp = 3.dp) {
    val p = LocalWPalette.current
    LinearProgressIndicator(fraction, GlanceModifier.fillMaxWidth().height(height), color = p.tone(kind), backgroundColor = p.track)
}

/** "12:30  Microeconomics": the time in the type's colour, the name beside it. */
@Composable
internal fun MiniRow(time: String, title: String, kind: WidgetKind?, strong: Boolean = false, lines: Int = 1) {
    val p = LocalWPalette.current
    Row(GlanceModifier.fillMaxWidth()) {
        Mono(time, 11f, kind?.let(p::tone) ?: p.muted)
        Spacer(GlanceModifier.width(8.dp))
        Text(title, style = style(12.sp, FontWeight.Bold, if (strong) p.text else p.soft), maxLines = lines)
    }
}

/** Signed out, out of date, or nothing published yet. */
@Composable
internal fun EmptyState(context: Context, message: String) {
    val p = LocalWPalette.current
    WidgetSurface(context) {
        Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Tinted(R.drawable.ic_widget_calendar, p.accent, 20.dp)
            Spacer(GlanceModifier.height(8.dp))
            Name("Vectra", 16f)
            Spacer(GlanceModifier.height(2.dp))
            Text(message, style = style(Caption, FontWeight.Medium, p.secondary), maxLines = 3)
        }
    }
}
