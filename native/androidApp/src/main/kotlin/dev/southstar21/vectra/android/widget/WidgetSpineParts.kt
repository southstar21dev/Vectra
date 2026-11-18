package dev.southstar21.vectra.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import dev.southstar21.vectra.android.R
import dev.southstar21.vectra.features.widgets.WidgetKind

// The time line's own pieces: the countdown ring, the nodes and the line between them.

private val Transparent = ColorProvider(Color.Transparent)

/** The countdown ring with its number and unit word inside. */
@Composable
internal fun Ring(context: Context, fraction: Float, kind: WidgetKind, value: String?, unit: String, icon: Int? = null, size: Dp = 50.dp) {
    val p = LocalWPalette.current
    Box(GlanceModifier.size(size), contentAlignment = Alignment.Center) {
        Image(ImageProvider(ringBitmap(context, size, 4.5.dp, fraction, p.tone(kind), p.track)), contentDescription = null, modifier = GlanceModifier.fillMaxSize())
        if (icon != null) {
            Tinted(icon, p.tone(kind), 20.dp)
        } else if (value != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Mono(value, if (value.length > 3) 13f else 17f, weight = FontWeight.Bold)
                Text(unit, style = style(8.sp, FontWeight.Bold, p.muted), maxLines = 1)
            }
        }
    }
}

/** The dot on the time line: a ring for the class on now, a check once it's over. */
@Composable
internal fun Node(kind: WidgetKind, live: Boolean, done: Boolean) {
    val p = LocalWPalette.current
    Box(GlanceModifier.size(12.dp), contentAlignment = Alignment.Center) {
        when {
            done -> Box(GlanceModifier.size(12.dp).cornerRadius(6.dp).background(p.check), contentAlignment = Alignment.Center) {
                Tinted(R.drawable.ic_widget_check, p.onCheck, 9.dp)
            }
            // A ring, not a filled dot: drawn, since Glance has no borders and the plate behind can be glass.
            live -> Image(
                ImageProvider(ringBitmap(LocalContext.current, 11.dp, 2.5.dp, 1f, p.tone(kind), Transparent)),
                contentDescription = null,
                modifier = GlanceModifier.size(11.dp),
            )
            else -> Box(GlanceModifier.size(7.dp).cornerRadius(4.dp).background(p.tone(kind))) {}
        }
    }
}

/** A stretch of the time line, [height] tall. Dashed across a break. */
@Composable
internal fun SpineLine(height: Dp, dashed: Boolean = false) {
    val p = LocalWPalette.current
    if (height <= 0.dp) return
    if (!dashed) {
        Box(GlanceModifier.width(1.5.dp).height(height).background(p.line)) {}
        return
    }
    Column {
        repeat((height.value / 6).toInt().coerceIn(1, 5)) {
            Box(GlanceModifier.width(1.5.dp).height(3.dp).background(p.line)) {}
            Spacer(GlanceModifier.height(3.dp))
        }
    }
}
