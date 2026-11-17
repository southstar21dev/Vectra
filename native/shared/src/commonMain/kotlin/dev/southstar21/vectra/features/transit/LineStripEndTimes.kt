package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext

/**
 * The next departures under one end of the strip. The first is the one on the line, in its colour;
 * the rest are quiet. Nothing when there is no later departure to add.
 */
@Composable
internal fun EndTimes(times: List<StripTime>, modifier: Modifier = Modifier) {
    if (times.size < 2) return
    val c = Vectra.colors
    Row(modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        times.forEachIndexed { i, t ->
            val color = when {
                i > 0 -> c.textSecondary
                t.soon -> c.tints.emerald.text
                t.kind == StripTimeKind.Live -> Vectra.monitor.live
                else -> Vectra.monitor.sched
            }
            VText(
                t.label,
                style = vtext(13.sp to 16.sp, if (i == 0) Weight.Extrabold else Weight.Bold, mono = true).tnum(),
                color = color,
                maxLines = 1,
            )
        }
    }
}
