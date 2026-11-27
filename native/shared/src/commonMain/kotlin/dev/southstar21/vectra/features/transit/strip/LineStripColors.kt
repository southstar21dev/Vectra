package dev.southstar21.vectra.features.transit.strip

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraColors
import dev.southstar21.vectra.core.designsystem.theme.parseHexColor

/** The colours one strip is drawn in. */
internal class StripColors(
    /** Track and stop rings: the route colour, shifted so it reads on the card. */
    val line: Color,
    /** Vehicle markers: the route colour itself. */
    val marker: Color,
    val markerInk: Color,
    /** The card behind the strip: halos around your stop and the markers. */
    val card: Color,
    /** Your stop's fill. */
    val you: Color,
    /** Greyed parts of the line. */
    val track: Color,
    /** Replacement buses: BKK's bus blue, whatever the line's colour. */
    val bus: Color = BusBlue,
    val busInk: Color = Color.White,
    /** The ring around a vehicle token and its heading point. */
    val ring: Color = Color.White,
    /** The ring around your stop on the line strip; the line's colour unless a theme sets it apart. */
    val youRing: Color = line,
    /** Your stop's centre on the line strip. */
    val youFill: Color = you,
) {
    /** The same, with the line drawn in [line] (the route map's own colour, say). */
    fun withLine(line: Color) = StripColors(line, marker, markerInk, card, you, track, bus, busInk, ring, if (youRing == this.line) line else youRing, youFill)
}

/** BKK's bus blue, for replacement buses. */
internal val BusBlue = Color(0xFF009EE3)

/** Route colours adjusted for the card: dark blues lift on dark cards, yellows deepen on white. */
@Composable
internal fun stripColors(routeColor: String?, routeTextColor: String?, card: Color, grey: Boolean = false): StripColors {
    val c = Vectra.colors
    val dark = c.isDark
    val track = if (dark) Color.White.copy(alpha = 0.12f) else Color(0xFF0F172A).copy(alpha = 0.12f)
    if (grey) return StripColors(c.textMuted, track, c.textMuted, card, track, track)
    val route = parseHexColor(routeColor?.takeIf { it.isNotEmpty() }) ?: Palette.Blue500
    val ink = parseHexColor(routeTextColor?.takeIf { it.isNotEmpty() }) ?: Color.White
    val l = route.luminance()
    val line = if (dark) when {
        l < 0.03f -> Palette.Slate400
        l < 0.2f -> lerp(route, Color.White, 0.3f)
        else -> route
    } else when {
        l < 0.03f -> Palette.Slate600
        l > 0.55f -> lerp(route, Color.Black, 0.25f)
        else -> route
    }
    // A black night-bus marker would vanish on a dark card.
    val (marker, markerInk) = if (dark && l < 0.03f) Palette.Slate300 to Palette.Slate900 else route to ink
    // Light: the marker's ring is drawn in ink over a card-coloured centre, so it never fades into
    // a pale line on the white card. Dark keeps the line colour around an ink centre.
    val youRing = if (dark) line else c.textPrimary
    val youFill = if (dark) c.textPrimary else card
    return StripColors(line, marker, markerInk, card, c.textPrimary, track, youRing = youRing, youFill = youFill)
}

/** The arrow before a direction's name: ink on a light card, where the muted grey fades into it. */
internal fun directionArrowTint(c: VectraColors): Color =
    if (c.isDark) c.textMuted else c.textPrimary
