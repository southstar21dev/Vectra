package dev.southstar21.vectra.features.schedule

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.Radius
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.plannerCard

/** The check-in orb with nothing to check in to: the ring drawn as dashes. */
@Composable
internal fun DashedOrb(modifier: Modifier = Modifier, size: Dp = 36.dp, color: Color = Vectra.colors.textMuted) {
    Canvas(modifier.size(size)) {
        val sw = 1.5.dp.toPx()
        val dash = 4.dp.toPx()
        drawCircle(
            color,
            radius = (this.size.minDimension - sw) / 2f,
            style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
        )
    }
}

/**
 * The schedule's empty drawing, made of its own parts: two faint planner cards, the first with a
 * dashed check-in orb where a class would sit.
 */
@Composable
internal fun GhostScheduleArt(modifier: Modifier = Modifier) {
    Column(modifier.width(236.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostCard(alpha = 0.7f, orb = true)
        GhostCard(alpha = 0.4f, orb = false)
    }
}

@Composable
private fun GhostCard(alpha: Float, orb: Boolean) {
    val c = Vectra.colors
    val bar = RoundedCornerShape(5.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .clearAndSetSemantics {}
            .plannerCard(Radius.Strip)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.width(36.dp).height(7.dp).background(c.borderPrimary, bar))
            Box(Modifier.fillMaxWidth(0.8f).height(10.dp).background(c.borderPrimary, bar))
            Box(Modifier.fillMaxWidth(0.5f).height(7.dp).background(c.borderSubtle, bar))
        }
        if (orb) DashedOrb(Modifier.padding(end = 2.dp)) else Box(Modifier.size(36.dp))
    }
}
