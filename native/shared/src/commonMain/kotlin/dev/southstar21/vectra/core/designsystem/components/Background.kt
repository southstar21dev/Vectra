package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraColors
import dev.southstar21.vectra.core.designsystem.theme.mixTransparent
import dev.southstar21.vectra.core.preferences.BackgroundKey

/**
 * An elliptical radial glow (radii [rx]×[ry], fading to transparent at [stop]), drawn
 * by scaling a circular gradient.
 */
fun DrawScope.ellipticalGlow(center: Offset, rx: Float, ry: Float, color: Color, stop: Float) {
    if (rx <= 0f || ry <= 0f) return
    withTransform({ scale(1f, ry / rx, center) }) {
        drawRect(
            Brush.radialGradient(0f to color, stop to Color.Transparent, center = center, radius = rx),
            topLeft = Offset(0f, center.y - rx),
            size = Size(size.width, rx * 2),
        )
    }
}

/**
 * The app canvas painted behind every route: surface + mesh grid / flat / aurora
 * per the appearance setting, plus the three background glow orbs.
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier, background: BackgroundKey = Vectra.appearance.background, glow: Boolean = true) {
    val c = Vectra.colors
    Canvas(modifier.fillMaxSize()) {
        drawRect(c.surfacePrimary)
        when (background) {
            BackgroundKey.Mesh -> drawMesh(c.meshLine, 32.dp.toPx())
            BackgroundKey.Flat -> Unit
            BackgroundKey.Aurora -> {
                ellipticalGlow(Offset(size.width * 0.15f, 0f), size.width * 0.8f, size.height * 0.6f, c.accentPrimary.mixTransparent(16f), 0.6f)
                ellipticalGlow(Offset(size.width * 0.9f, size.height), size.width * 0.7f, size.height * 0.55f, VectraColors.AccentSecondary.mixTransparent(14f), 0.6f)
            }
        }
        if (glow) drawBackgroundGlow(c)
    }
}

fun DrawScope.drawMesh(line: Color, cell: Float) {
    val px = 1.dp.toPx()
    var x = 0f
    while (x < size.width) { drawRect(line, Offset(x, 0f), Size(px, size.height)); x += cell }
    var y = 0f
    while (y < size.height) { drawRect(line, Offset(0f, y), Size(size.width, px)); y += cell }
}

/** Three luminous orbs behind the content. */
private fun DrawScope.drawBackgroundGlow(c: VectraColors) {
    val w = size.width
    val h = size.height
    fun orb(diameter: Float, cx: Float, cy: Float, color: Color, opacity: Float, stop: Float) {
        val r = diameter / 2f
        drawCircle(
            Brush.radialGradient(0f to color.copy(alpha = color.alpha * opacity), stop to Color.Transparent, center = Offset(cx, cy), radius = r),
            radius = r,
            center = Offset(cx, cy),
        )
    }
    val d1 = 650.dp.toPx()
    orb(d1, w + 0.10f * w - d1 / 2f, -0.15f * h + d1 / 2f, c.glowPrimary, 0.6f, 0.7f)
    val d2 = 550.dp.toPx()
    orb(d2, -0.10f * w + d2 / 2f, h + 0.10f * h - d2 / 2f, c.glowSecondary, 0.5f, 0.7f)
    orb(400.dp.toPx(), 0.45f * w, 0.35f * h, c.accentPrimary.mixTransparent(20f), 0.3f, 0.75f)
}

/** Surface fade behind the nav and status bar. */
fun navFadeBrush(surface: Color, toTop: Boolean): Brush {
    val stops = arrayOf(
        0f to surface,
        0.30f to surface.mixTransparent(95f),
        0.60f to surface.mixTransparent(60f),
        0.85f to surface.mixTransparent(20f),
        1f to Color.Transparent,
    )
    return if (toTop) Brush.verticalGradient(*stops.map { (1f - it.first) to it.second }.reversed().toTypedArray())
    else Brush.verticalGradient(*stops)
}
