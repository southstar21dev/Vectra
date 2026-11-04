package dev.southstar21.vectra.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.resources.Res
import dev.southstar21.vectra.resources.inter_variable
import dev.southstar21.vectra.resources.jetbrains_mono_variable
import org.jetbrains.compose.resources.Font

/** OpenType tabular numerals, for digits that shouldn't shift as they change. */
internal const val Tnum = "tnum"

internal fun TextStyle.tnum() = copy(fontFeatureSettings = Tnum)

@Immutable
data class VectraFonts(val sans: FontFamily, val mono: FontFamily)

private val Weights = listOf(400, 500, 600, 700, 800, 900)

/** Inter Variable + JetBrains Mono Variable, one instance per weight the app uses. */
@Composable
fun rememberVectraFonts(): VectraFonts {
    val sans = FontFamily(
        Weights.map { w ->
            Font(
                Res.font.inter_variable,
                weight = FontWeight(w),
                variationSettings = FontVariation.Settings(FontVariation.weight(w)),
            )
        },
    )
    val mono = FontFamily(
        Weights.filter { it <= 800 }.map { w ->
            Font(
                Res.font.jetbrains_mono_variable,
                weight = FontWeight(w),
                variationSettings = FontVariation.Settings(FontVariation.weight(w)),
            )
        },
    )
    return VectraFonts(sans, mono)
}

/** Type scale: (font size, line height) pairs, from the smallest to the largest role. */
object TypeScale {
    val Micro = 10.sp to 14.sp
    val Caption = 12.sp to 16.sp
    val Body = 14.sp to 20.sp
    val BodyLarge = 16.sp to 24.sp
    val Subtitle = 18.sp to 28.sp
    val Title = 20.sp to 28.sp
    val Headline = 24.sp to 32.sp
    val Display = 36.sp to 40.sp
}

/** The font weights the app uses; [rememberVectraFonts] loads an instance of each. */
object Weight {
    val Normal = FontWeight(400)
    val Medium = FontWeight(500)
    val Semibold = FontWeight(600)
    val Bold = FontWeight(700)
    val Extrabold = FontWeight(800)
    val Black = FontWeight(900)
}

private val TrimNone = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** Build a text style from a [TypeScale] step, a [Weight] and optional [Tracking] / line height. */
fun vtext(
    size: Pair<TextUnit, TextUnit> = TypeScale.Body,
    weight: FontWeight = Weight.Normal,
    tracking: TextUnit = 0.em,
    lineHeight: TextUnit? = null,
    mono: Boolean = false,
): TextStyle = TextStyle(
    fontSize = size.first,
    lineHeight = lineHeight ?: size.second,
    fontWeight = weight,
    letterSpacing = tracking,
    fontFamily = if (mono) MonoMarker else null,
    lineHeightStyle = TrimNone,
)

/** Placeholder resolved to the real mono family inside VText (fonts load at composition). */
internal val MonoMarker = FontFamily.Monospace

/** Letter spacing, in em so it scales with the font size. */
object Tracking {
    val Tighter = (-0.05).em
    val Tight = (-0.025).em
    val Heading = (-0.02).em
    val Wide = 0.025.em
    val Wider = 0.05.em
    val Widest = 0.1.em
}

/** Named styles for the app's text hierarchy, which leans on weight more than size. */
object VType {
    val Body = vtext(TypeScale.Body)
    val Caption = vtext(TypeScale.Caption)
    val Label = vtext(TypeScale.Caption, Weight.Semibold)
    val Micro = vtext(TypeScale.Micro, Weight.Bold)
    val Title = vtext(TypeScale.BodyLarge, Weight.Extrabold, Tracking.Heading, lineHeight = 19.2.sp)
    val SectionTitle = vtext(TypeScale.Subtitle, Weight.Black, Tracking.Tight, lineHeight = 18.sp)
    val Display = vtext(TypeScale.Headline, Weight.Black, Tracking.Heading)
}
