package dev.southstar21.vectra.core.designsystem.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * A border whose top edge has its own colour. The
 * specular top colour fades into the side colour over the corner radius.
 */
fun Modifier.specularBorder(shape: Shape, side: Color, top: Color, width: Float = 1f): Modifier =
    if (side == top) border(width.dp, side, shape)
    else border(
        width.dp,
        Brush.verticalGradient(0f to top, 0.5f to side, 1f to side, startY = 0f, endY = 48f),
        shape,
    )

/** The glass card surface. */
@Composable
fun Modifier.glass(shape: Shape, colors: VectraColors = Vectra.colors, hairlineInLight: Boolean = false): Modifier {
    val side = if (!colors.isDark && hairlineInLight) rgba(15, 23, 42, 0.08f) else colors.glassBorder
    val top = if (!colors.isDark && hairlineInLight) rgba(15, 23, 42, 0.08f) else colors.glassBorderSpecular
    return this
        .boxShadow(shape, colors.glassShadow)
        .background(colors.glassBg, shape)
        .insetShadow(shape, colors.glassShadow)
        .specularBorder(shape, side, top)
}

/** Depth of dialogs and solid cards. */
fun cardDepthShadows(dark: Boolean): List<BoxShadow> = if (dark) listOf(
    BoxShadow(y = 1f, color = rgba(255, 255, 255, 0.08f), inset = true),
    BoxShadow(y = 1f, blur = 2f, color = rgba(0, 0, 0, 0.35f)),
    BoxShadow(y = 16f, blur = 36f, spread = -14f, color = rgba(0, 0, 0, 0.7f)),
) else listOf(
    BoxShadow(y = 1f, color = rgba(255, 255, 255, 0.95f), inset = true),
    BoxShadow(blur = 16f, spread = -4f, color = rgba(15, 23, 42, 0.12f)),
    BoxShadow(y = 1f, blur = 3f, color = rgba(15, 23, 42, 0.09f)),
    BoxShadow(y = 14f, blur = 30f, spread = -12f, color = rgba(15, 23, 42, 0.22f)),
)

/** The pill depth recipes behind buttons, toggles and selected options. */
@Immutable
enum class PillTone { Accent, Emerald, Blue, Gray, Destructive }

@Composable
fun Modifier.pillDepth(shape: Shape, tone: PillTone, colors: VectraColors = Vectra.colors): Modifier {
    val dark = colors.isDark
    val (fill, border, glow) = when (tone) {
        PillTone.Accent -> Triple(colors.accentPrimary, colors.accentPrimary.mix(70f, Color.White), colors.accentPrimary)
        PillTone.Emerald -> Triple(Palette.Emerald600, rgba(110, 231, 183, 0.6f), Palette.Emerald500)
        PillTone.Blue -> Triple(Palette.Blue600, rgba(147, 197, 253, 0.6f), Palette.Blue600)
        PillTone.Gray -> Triple(
            Palette.Slate600,
            if (dark) rgba(148, 163, 184, 0.5f) else rgba(100, 116, 139, 0.6f),
            Palette.Slate600,
        )
        PillTone.Destructive -> Triple(Palette.Red600, rgba(252, 165, 165, 0.6f), Palette.Red500)
    }
    val shadows = if (dark) {
        val (bloom, insetA) = when (tone) {
            PillTone.Accent -> 20f to 0.4f
            PillTone.Gray -> 16f to 0.35f
            else -> 18f to 0.4f
        }
        val glowA = if (tone == PillTone.Accent) 0.35f else 0.35f
        listOf(
            BoxShadow(blur = bloom, color = glow.copy(alpha = glowA)),
            BoxShadow(y = 4f, blur = 12f, color = glow.copy(alpha = 0.25f)),
            BoxShadow(y = 1f, color = rgba(255, 255, 255, insetA), inset = true),
        )
    } else listOf(
        BoxShadow(y = 4f, blur = 14f, color = glow.copy(alpha = 0.35f)),
        BoxShadow(y = 1f, blur = 3f, color = rgba(0, 0, 0, if (tone == PillTone.Gray) 0.1f else 0.08f)),
        BoxShadow(y = 1f, color = rgba(255, 255, 255, if (tone == PillTone.Gray) 0.35f else 0.6f), inset = true),
    )
    return this
        .boxShadow(shape, shadows)
        .background(fill, shape)
        .insetShadow(shape, shadows)
        .border(1.dp, border, shape)
}

/** Glass pill depth (outline buttons). */
@Composable
fun Modifier.pillGlass(shape: Shape, colors: VectraColors = Vectra.colors): Modifier {
    val shadows = listOf(
        BoxShadow(y = 2f, blur = 8f, color = rgba(0, 0, 0, 0.08f)),
        BoxShadow(y = 1f, color = rgba(255, 255, 255, 0.15f), inset = true),
    )
    return this
        .boxShadow(shape, shadows)
        .background(colors.glassBg, shape)
        .insetShadow(shape, shadows)
        .specularBorder(shape, colors.glassBorder, colors.glassBorderSpecular)
}

/** Glow behind the active nav pill. */
fun navPillGlow(accent: Color) = listOf(
    BoxShadow(blur = 20f, color = accent.mixTransparent(35f)),
    BoxShadow(y = 4f, blur = 12f, color = accent.mixTransparent(25f)),
)
