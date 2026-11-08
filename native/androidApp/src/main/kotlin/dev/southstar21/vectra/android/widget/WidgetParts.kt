package dev.southstar21.vectra.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
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
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import dev.southstar21.vectra.android.R

// The pieces the widgets share, after the app's components of the same names.

/** The stacked start/end time block on the right of the app's class cards. */
@Composable
internal fun StackedTime(start: String, end: String?) {
    Column(
        GlanceModifier
            .background(ImageProvider(R.drawable.widget_chip))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(start, style = style(Caption, FontWeight.Bold, mono = true))
        if (end != null) {
            Spacer(GlanceModifier.height(3.dp))
            Box(GlanceModifier.width(14.dp).height(1.dp).background(WColors.hairline)) {}
            Spacer(GlanceModifier.height(3.dp))
            Text(end, style = style(Caption, FontWeight.Bold, WColors.secondary, mono = true))
        }
    }
}

/** The raised accent pill (pillDepth Accent). */
@Composable
internal fun AccentPill(text: String) {
    Box(GlanceModifier.background(ImageProvider(R.drawable.widget_pill)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, style = style(Caption, FontWeight.Bold, WColors.onAccent), maxLines = 1)
    }
}

/** A progress bar in the accent. */
@Composable
internal fun Progress(fraction: Float, height: Dp = 6.dp) {
    LinearProgressIndicator(
        progress = fraction,
        modifier = GlanceModifier.fillMaxWidth().height(height),
        color = WColors.accent,
        backgroundColor = WColors.track,
    )
}

/** Signed out, or nothing published yet. */
@Composable
internal fun EmptyState(context: Context, message: String) {
    WidgetSurface(context) {
        Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Tinted(R.drawable.ic_widget_calendar, WColors.accent, 20.dp)
            Spacer(GlanceModifier.height(8.dp))
            Text("Vectra", style = style(TitleSize, FontWeight.Bold))
            Spacer(GlanceModifier.height(2.dp))
            Text(message, style = style(Caption, FontWeight.Medium, WColors.secondary), maxLines = 3)
        }
    }
}

@Composable
internal fun Place(location: String) {
    if (location.isBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Tinted(R.drawable.ic_widget_pin, WColors.secondary, 14.dp)
        Spacer(GlanceModifier.width(5.dp))
        Text(location, style = style(Caption, FontWeight.Medium, WColors.secondary), maxLines = 1)
    }
}
