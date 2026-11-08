package dev.southstar21.vectra.android.widget

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.southstar21.vectra.android.MainActivity
import dev.southstar21.vectra.android.R
import dev.southstar21.vectra.features.widgets.WidgetKind
import dev.southstar21.vectra.platform.AndroidNotifier

// The app's tokens (VectraColors), as day/night pairs the launcher resolves with the system theme.

internal object WColors {
    val text = ColorProvider(day = Color(0xFF0F172A), night = Color(0xFFF8FAFC))
    val secondary = ColorProvider(day = Color(0xFF334155), night = Color(0xFF94A3B8))
    val muted = ColorProvider(day = Color(0xFF64748B), night = Color(0xFF7C8AA0))
    val onAccent = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF04121B))
    val hairline = ColorProvider(day = Color(0x140F172A), night = Color(0x14FFFFFF))
    val overlay = ColorProvider(day = Color(0x08000000), night = Color(0x09FFFFFF))
    val track = ColorProvider(day = Color(0x140F172A), night = Color(0x14FFFFFF))
    val accent = ColorProvider(day = Color(0xFF0284C7), night = Color(0xFF38BDF8))
    val sky = accent
    val emerald = ColorProvider(day = Color(0xFF059669), night = Color(0xFF34D399))
    val violet = ColorProvider(day = Color(0xFF6D28D9), night = Color(0xFFC4B5FD))
    val amber = ColorProvider(day = Color(0xFFB45309), night = Color(0xFFFBBF24))
    val purple = ColorProvider(day = Color(0xFF7C3AED), night = Color(0xFFC084FC))
}

/** The session-type tones of ScheduleCard: lecture sky, seminar emerald, lab purple, exam violet. */
internal fun WidgetKind.tone(): ColorProvider = when (this) {
    WidgetKind.Lecture, WidgetKind.Other -> WColors.sky
    WidgetKind.Seminar -> WColors.emerald
    WidgetKind.Lab -> WColors.purple
    WidgetKind.Exam -> WColors.violet
    WidgetKind.Task -> WColors.amber
    WidgetKind.Shift -> WColors.purple
}

/** Personal items wear an icon instead of the dot. */
@DrawableRes
internal fun WidgetKind.icon(): Int? = when (this) {
    WidgetKind.Task -> R.drawable.ic_widget_task
    WidgetKind.Shift -> R.drawable.ic_widget_shift
    else -> null
}

internal fun style(
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    color: ColorProvider = WColors.text,
    mono: Boolean = false,
) = TextStyle(color = color, fontSize = size, fontWeight = weight, fontFamily = if (mono) FontFamily.Monospace else null)

internal val Micro = 10.sp
internal val Caption = 12.sp
internal val TitleSize = 16.sp

/** The whole widget opens the Schedule tab. */
internal fun GlanceModifier.opensSchedule(context: Context): GlanceModifier = clickable(
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AndroidNotifier.EXTRA_URL, "/schedule"),
    ),
)

/** The widget canvas: the app background with its aurora, or lit in the accent while a class runs. */
@Composable
internal fun WidgetSurface(
    context: Context,
    live: Boolean = false,
    padding: Dp = 14.dp,
    content: @Composable () -> Unit,
) {
    Box(
        GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(if (live) R.drawable.widget_live_surface else R.drawable.widget_surface))
            .opensSchedule(context)
            .padding(padding),
    ) { content() }
}

@Composable
internal fun Tinted(@DrawableRes res: Int, tint: ColorProvider, size: Dp) {
    Image(ImageProvider(res), contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = GlanceModifier.size(size))
}

/** StatusLabel: a tone dot (or the item's icon) beside a quiet grey label. */
@Composable
internal fun StatusLabel(text: String, kind: WidgetKind, color: ColorProvider = WColors.secondary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ToneMark(kind)
        Spacer(GlanceModifier.width(6.dp))
        Text(text, style = style(Caption, FontWeight.Medium, color), maxLines = 1)
    }
}

@Composable
internal fun ToneMark(kind: WidgetKind) {
    val icon = kind.icon()
    if (icon != null) {
        Tinted(icon, kind.tone(), 14.dp)
    } else {
        Box(GlanceModifier.size(14.dp), contentAlignment = Alignment.Center) {
            Image(
                ImageProvider(R.drawable.widget_dot),
                contentDescription = null,
                colorFilter = ColorFilter.tint(kind.tone()),
                modifier = GlanceModifier.size(14.dp),
                alpha = 0.2f,
            )
            Tinted(R.drawable.widget_dot, kind.tone(), 8.dp)
        }
    }
}
