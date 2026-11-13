package dev.southstar21.vectra.features.schedule

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.rgba
import dev.southstar21.vectra.core.preferences.TimeFormat
import dev.southstar21.vectra.core.time.formatMinuteOfDay
import dev.southstar21.vectra.core.time.formatTime

/** The day list's colours (builder spec 1, "Colour tokens"). */
@Immutable
internal class DayColors(val isDark: Boolean) {
    val card = if (isDark) Color(0xFF0E0F14) else Color.White
    /** A cancelled class: darker than a card, still flat (never carved in). */
    val cancelledCard = if (isDark) Color(0xFF08090B) else Color(0xFFE2E8F0)
    val strip = if (isDark) Color(0xFF14151C) else Color(0xFFF8FAFC)
    val border = if (isDark) rgba(255, 255, 255, 0.08f) else rgba(15, 23, 42, 0.08f)
    val borderTop = if (isDark) rgba(255, 255, 255, 0.2f) else rgba(15, 23, 42, 0.1f)
    val smallBorderTop = if (isDark) rgba(255, 255, 255, 0.18f) else rgba(15, 23, 42, 0.18f)
    val cardShadow = listOf(BoxShadow(y = 16f, blur = 36f, spread = -8f, color = if (isDark) rgba(0, 0, 0, 0.5f) else rgba(15, 23, 42, 0.12f)))
    val stripShadow = listOf(BoxShadow(y = 8f, blur = 18f, spread = -6f, color = if (isDark) rgba(0, 0, 0, 0.75f) else rgba(15, 23, 42, 0.1f)))
    val smallShadow = listOf(BoxShadow(y = 10f, blur = 22f, spread = -10f, color = if (isDark) rgba(0, 0, 0, 0.8f) else rgba(15, 23, 42, 0.14f)))
    val text = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val secondary = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569)
    val muted = if (isDark) Color(0xFF7C8AA0) else Color(0xFF64748B)
    val room = if (isDark) Color(0xFFCBD5E1) else Color(0xFF334155)
    val rule = if (isDark) rgba(255, 255, 255, 0.18f) else rgba(15, 23, 42, 0.18f)
    val line = if (isDark) rgba(255, 255, 255, 0.16f) else rgba(15, 23, 42, 0.14f)
    val ring = if (isDark) rgba(255, 255, 255, 0.25f) else rgba(15, 23, 42, 0.25f)
    val nowFill = if (isDark) rgba(255, 255, 255, 0.06f) else rgba(15, 23, 42, 0.05f)
    val nowEdge = if (isDark) rgba(255, 255, 255, 0.12f) else rgba(15, 23, 42, 0.12f)
    val nowBorder = if (isDark) rgba(255, 255, 255, 0.14f) else rgba(15, 23, 42, 0.14f)
    val edgeNear = if (isDark) Color(0xFF13141B) else Color(0xFFE9EDF3)
    val edgeFar = if (isDark) Color(0xFF101117) else Color(0xFFE2E7EE)
    val placeholder = if (isDark) rgba(255, 255, 255, 0.07f) else rgba(15, 23, 42, 0.07f)
    val placeholderStrip = if (isDark) Color(0xFF121318) else Color(0xFFE9EDF3)
    /** The class open in the wide Schedule tab's side pane: a brighter neutral edge, no colour. */
    val selectedBorder = if (isDark) rgba(255, 255, 255, 0.42f) else rgba(15, 23, 42, 0.38f)
    val clashBorder = rgba(248, 113, 113, 0.45f)
    val clashIcon = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)
    val clashText = if (isDark) Color(0xFFFCA5A5) else Color(0xFFB91C1C)
    val dashed = rgba(148, 163, 184, 0.6f)
    fun timeLeft(tone: TimeLeftTone): Color = when (tone) {
        TimeLeftTone.Plenty -> if (isDark) Color(0xFF34D399) else Color(0xFF059669)
        TimeLeftTone.Soon -> if (isDark) Color(0xFFFBBF24) else Color(0xFFB45309)
        TimeLeftTone.Urgent -> if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)
    }
}

@Composable
internal fun rememberDayColors(): DayColors {
    val dark = Vectra.colors.isDark
    return remember(dark) { DayColors(dark) }
}

internal val CardShape = RoundedCornerShape(22.dp)
internal val StripShape = RoundedCornerShape(18.dp)
internal val SmallShape = RoundedCornerShape(16.dp)

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/** The course colour, stepped toward white (dark) or black (light) until it reads on [bg]. */
internal fun readableOn(color: Color, bg: Color, dark: Boolean): Color {
    var out = color
    var step = 0
    while (contrast(out, bg) < 4.5f && step < 10) {
        step++
        out = lerp(color, if (dark) Color.White else Color.Black, step / 10f)
    }
    return out
}

/** "08:00 – 09:30" for a span in minutes; a span past midnight shows the clock time. */
internal fun rangeLabel(start: Int, end: Int, format: TimeFormat): String =
    "${formatTime(formatMinuteOfDay(start), format)} – ${formatTime(formatMinuteOfDay(end % (24 * 60)), format)}"
