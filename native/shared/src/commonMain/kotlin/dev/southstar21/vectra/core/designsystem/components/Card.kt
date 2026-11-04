package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.Tint
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.designsystem.theme.Radius

/** Neutral chip. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Vectra.colors
    val shape = Radius.Tag
    Row(
        modifier.background(c.surfaceOverlay, shape).border(1.dp, c.borderSubtle, shape).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) VIcon(icon, size = 12.dp, tint = c.textSecondary)
        VText(text, style = vtext(TypeScale.Micro, Weight.Bold), color = c.textSecondary, maxLines = 1)
    }
}

enum class StatusTone { Amber, Blue, Emerald, Green, Purple, Red, Sky, Violet, Neutral }

@Composable
fun StatusTone.color() = Vectra.colors.let { c ->
    when (this) {
        StatusTone.Amber -> c.tints.amber.text
        StatusTone.Blue -> c.tints.blue.text
        StatusTone.Emerald -> c.tints.emerald.text
        StatusTone.Green -> c.tints.green.text
        StatusTone.Purple -> c.tints.purple.text
        StatusTone.Red -> c.tints.red.text
        StatusTone.Sky -> c.tints.sky.text
        StatusTone.Violet -> c.tints.violet.text
        StatusTone.Neutral -> c.textMuted
    }
}

/** Inline metadata label: a tone-coloured icon or dot beside a quiet grey label. */
@Composable
fun StatusLabel(text: String, modifier: Modifier = Modifier, tone: StatusTone = StatusTone.Neutral, icon: ImageVector? = null) {
    val toneColor = tone.color()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) VIcon(icon, size = 14.dp, tint = toneColor)
        else Box(Modifier.size(14.dp).background(toneColor.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).background(toneColor, CircleShape))
        }
        VText(text, style = vtext(TypeScale.Caption, Weight.Semibold), color = Vectra.colors.textSecondary, maxLines = 1)
    }
}

/** Icon chip used by section headers: tint background, border and ink, 12dp corners. */
@Composable
fun IconChip(icon: ImageVector, tint: Tint, modifier: Modifier = Modifier, size: Int = 36, iconSize: Int = 18) {
    val shape = Radius.Control
    Box(
        modifier.size(size.dp).background(tint.bg, shape).border(1.dp, tint.border, shape),
        contentAlignment = Alignment.Center,
    ) { VIcon(icon, size = iconSize.dp, tint = tint.text) }
}
