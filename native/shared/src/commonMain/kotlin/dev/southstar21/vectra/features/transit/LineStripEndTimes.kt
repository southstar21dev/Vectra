package dev.southstar21.vectra.features.transit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext

/**
 * Quiet follow-up departure times under one side of the aligned strip ("then 14', 19'").
 * Primary ETA is already displayed in [AlignedStripHeader], so this never repeats it.
 */
@Composable
internal fun EndTimes(times: List<StripTime>, modifier: Modifier = Modifier) {
    if (times.isEmpty()) return
    val c = Vectra.colors
    val list = times.joinToString(", ") { it.label }
    VText(
        "then $list",
        modifier,
        style = vtext(12.sp to 16.sp, Weight.Medium, mono = true).tnum(),
        color = c.textMuted,
        maxLines = 1,
    )
}
