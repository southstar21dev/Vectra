package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.designsystem.theme.Palette

/**
 * Full ordered stop list for one direction — the "Show all stops" expansion.
 * The vehicle row sits above the vehicle's next stop, the tracked stop is
 * highlighted and the termini carry start/end tags.
 */
@Composable
fun DirectionStopList(
    direction: DirectionView,
    routeEmoji: String,
    routeName: String,
    accent: DirectionAccent,
    modifier: Modifier = Modifier,
    routeId: String? = null,
) {
    val c = Vectra.colors
    val stops = direction.stops
    val progress = direction.progress
    val displayRouteName = formatRouteDisplayName(routeName, routeId?.takeIf { it.isNotEmpty() } ?: direction.arrival?.routeId, true)
    val violet = accent == DirectionAccent.Violet
    val approx = direction.approxVehicle != null
    val divider = c.borderSubtle.copy(alpha = c.borderSubtle.alpha * 0.6f)

    Column(modifier.fillMaxWidth()) {
        // A hairline above every row but the first.
        val separator: @Composable (Boolean) -> Unit = { firstRow ->
            if (!firstRow) Box(Modifier.fillMaxWidth().height(1.dp).background(divider))
        }
        stops.forEachIndexed { i, s ->
            val vehicleHere = progress.vehicleIndex >= 0 && i == progress.vehicleIndex
            if (vehicleHere) {
                separator(i == 0)
                val (bg, dot, ink) = when {
                    approx -> Triple(Palette.Pink500.copy(alpha = 0.1f), Palette.Pink400, Palette.Pink500)
                    violet -> Triple(Palette.Violet500.copy(alpha = 0.1f), Palette.Violet400, Palette.Violet500)
                    else -> Triple(Palette.Emerald500.copy(alpha = 0.1f), Palette.Emerald400, c.tints.emerald.text)
                }
                Row(
                    Modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(dot, CircleShape))
                    VText("$routeEmoji $displayRouteName · vehicle here", style = vtext(TypeScale.Caption, Weight.Bold), color = ink, maxLines = 1)
                }
            }
            separator(i == 0 && !vehicleHere)
            val selected = s.isSelected == true
            val tracked = s.isTracked
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (selected) c.accentPrimary.copy(alpha = 0.1f) else Color.Transparent)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    selected -> RingDot(c.accentPrimary, c.accentPrimary.copy(alpha = 0.5f), 2.dp)
                    tracked -> RingDot(Palette.Blue400, Palette.Blue400.copy(alpha = 0.4f), 1.dp)
                    else -> Box(Modifier.size(8.dp).background(Palette.Zinc500, CircleShape))
                }
                VText(
                    s.name,
                    Modifier.weight(1f),
                    vtext(TypeScale.Body, if (selected) Weight.Bold else if (tracked) Weight.Semibold else Weight.Normal),
                    if (selected || tracked) c.textPrimary else c.textSecondary,
                )
                when {
                    selected -> VText("This stop", style = vtext(TypeScale.Micro, Weight.Bold), color = c.tints.blue.text)
                    tracked -> VText("Tracked", style = vtext(TypeScale.Micro, Weight.Semibold), color = c.tints.blue.text.copy(alpha = 0.8f))
                    i == 0 -> VText("Start", style = vtext(TypeScale.Micro), color = c.textMuted)
                    i == stops.size - 1 -> VText("End", style = vtext(TypeScale.Micro), color = c.textMuted)
                }
            }
        }
    }
}

/** An 8dp dot with a solid ring of [width] drawn around it. */
@Composable
private fun RingDot(fill: Color, ring: Color, width: Dp) {
    Box(
        Modifier
            .size(8.dp)
            .boxShadow(CircleShape, listOf(BoxShadow(spread = width.value, color = ring)))
            .background(fill, CircleShape),
    )
}
