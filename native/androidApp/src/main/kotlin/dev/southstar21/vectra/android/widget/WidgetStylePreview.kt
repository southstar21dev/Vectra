package dev.southstar21.vectra.android.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext

// The style screen's pictures, drawn in Compose: a home-screen wallpaper with the widget on it in
// the picked look, and the small tiles for each look.

private val Wallpaper = Brush.linearGradient(listOf(Color(0xFF3B4C8F), Color(0xFF7A3E6E), Color(0xFF1F6B6A)))

/** The colours a look draws in, for these pictures. */
private class Ink(val plate: Brush, val rim: Color, val text: Color, val soft: Color, val line: Color, val tones: List<Color>)

private fun ink(look: WidgetLook, systemDark: Boolean): Ink {
    val dark = look == WidgetLook.Dark || (look == WidgetLook.System && systemDark)
    return when {
        look == WidgetLook.Clear -> Ink(
            Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x14FFFFFF))), Color(0x3DFFFFFF), Color.White, Color(0xB8FFFFFF), Color(0x4DFFFFFF),
            List(3) { Color.White },
        )
        dark -> Ink(
            Brush.verticalGradient(listOf(Color(0xFF14151C), Color(0xFF0D0E13))), Color(0x12FFFFFF), Color(0xFFF8FAFC), Color(0xFF94A3B8), Color(0x29FFFFFF),
            listOf(Color(0xFF38BDF8), Color(0xFF34D399), Color(0xFFC084FC)),
        )
        else -> Ink(
            Brush.verticalGradient(listOf(Color.White, Color(0xFFF4F6FA))), Color(0x0F0F172A), Color(0xFF0F172A), Color(0xFF334155), Color(0x240F172A),
            listOf(Color(0xFF0284C7), Color(0xFF059669), Color(0xFF7C3AED)),
        )
    }
}

private val Rows = listOf(Triple("09:30", "LECTURE", "Data Structures"), Triple("12:30", "SEMINAR", "Microeconomics"), Triple("14:15", "LAB", "Statistics Lab"))

/** The widget on a wallpaper, in [look]. */
@Composable
internal fun StylePreview(look: WidgetLook) {
    val i = ink(look, androidx.compose.foundation.isSystemInDarkTheme())
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Wallpaper).padding(horizontal = 14.dp, vertical = 22.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(i.plate).border(1.dp, i.rim, RoundedCornerShape(24.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Rows.forEachIndexed { n, (time, type, name) ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VText(time, Modifier.width(40.dp), vtext(12.sp to 16.sp, Weight.Bold).copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), i.text)
                    Box(Modifier.padding(top = 4.dp).size(if (n == 0) 10.dp else 7.dp).clip(CircleShape).background(i.tones[n]))
                    Column {
                        VText(type, style = vtext(10.sp to 12.sp, Weight.Bold).copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = i.tones[n])
                        VText(name, style = vtext(14.sp to 18.sp, Weight.Bold), color = i.text)
                        if (n == 0) {
                            Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(i.line)) {
                                Box(Modifier.fillMaxWidth(0.4f).height(3.dp).background(i.tones[0]))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A look tile's little sketch: a card with a node and two lines. */
@Composable
internal fun LookSketch(look: WidgetLook) {
    val systemPlate = Brush.linearGradient(0.5f to Color(0xFF14151C), 0.5f to Color.White)
    val i = ink(look, true)
    val bg = if (look == WidgetLook.Clear) Wallpaper else Brush.linearGradient(listOf(Color(0xFF0F1015), Color(0xFF0F1015)))
    Box(Modifier.fillMaxSize().background(bg).padding(horizontal = 7.dp, vertical = 8.dp)) {
        val plate = when (look) {
            WidgetLook.System -> systemPlate
            WidgetLook.Light -> ink(WidgetLook.Light, false).plate
            else -> i.plate
        }
        val text = if (look == WidgetLook.Light) Color(0xFF0F172A) else i.text
        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)).background(plate).border(1.dp, i.rim, RoundedCornerShape(9.dp)).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF38BDF8)))
                Box(Modifier.width(24.dp).height(4.dp).clip(CircleShape).background(if (look == WidgetLook.System) Color(0xFF94A3B8) else text))
            }
            Box(Modifier.padding(start = 9.dp).width(30.dp).height(3.dp).clip(CircleShape).background(text.copy(alpha = 0.5f)))
            Box(Modifier.padding(start = 9.dp).width(20.dp).height(3.dp).clip(CircleShape).background(text.copy(alpha = 0.5f)))
        }
    }
}
