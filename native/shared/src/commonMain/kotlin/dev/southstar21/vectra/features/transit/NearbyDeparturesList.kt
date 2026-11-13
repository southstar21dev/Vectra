package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.SleepingIcon
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.util.roundHalfUpToLong
import dev.southstar21.vectra.core.designsystem.theme.Palette

/**
 * The lines at the stop nearest you, one row per line with both directions:
 * a heading with the stop, its distance and the walk, then hairline rows.
 */
@Composable
fun NearbyDeparturesList(
    items: List<RadarDepartureItem>,
    status: NearbyLocationStatus,
    modifier: Modifier = Modifier,
    maxLines: Int = 3,
) {
    val c = Vectra.colors
    val lead = items.firstOrNull()
    val groups = remember(items, lead, maxLines) { if (lead != null) groupByLine(items, lead.stopId, maxLines) else emptyList() }

    if (lead == null || groups.isEmpty()) {
        Row(
            modifier.padding(top = 12.dp).padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SleepingIcon(size = 16.dp, tint = if (c.isDark) Palette.Indigo300 else Palette.Indigo400)
            VText(
                when (status) {
                    NearbyLocationStatus.Tracking -> "No departures near you right now"
                    NearbyLocationStatus.Failed -> "Allow location to see departures"
                    else -> "Departures appear once you are located"
                },
                style = vtext(TypeScale.Body, Weight.Semibold),
                color = c.textSecondary,
            )
        }
        return
    }

    Column(modifier) {
        val heading = vtext(11.sp to 16.5.sp, Weight.Bold, Tracking.Wider)
        Row(
            Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VText(
                "${lead.stopName} · ${roundHalfUpToLong(lead.distanceMeters)} m".uppercase(),
                Modifier.weight(1f),
                heading,
                c.textMuted,
                maxLines = 1,
            )
        }
        groups.forEachIndexed { index, group ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RouteBadge(group.shortName, group.color, group.textColor, size = RouteBadgeSize.Md)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (departure in group.directions) {
                        val tone = when {
                            departure.countdownMinutes <= 0 -> c.tints.emerald.text
                            departure.feasibility == Feasibility.Hurry -> c.tints.amber.text
                            else -> c.textPrimary
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            VText(
                                "→ ${departure.headsign}",
                                Modifier.weight(1f),
                                vtext(TypeScale.Caption),
                                c.textSecondary,
                                maxLines = 1,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (departure.isPredicted) LiveSignalIcon(size = 16.dp, tint = c.tints.emerald.text)
                                else VIcon(RemixIcons.TimeLine, size = 16.dp, tint = c.textMuted)
                                VText(
                                    formatPrimeCountdown(departure.countdownMinutes),
                                    style = vtext(18.sp to 18.sp, Weight.Extrabold, mono = true),
                                    color = tone,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

