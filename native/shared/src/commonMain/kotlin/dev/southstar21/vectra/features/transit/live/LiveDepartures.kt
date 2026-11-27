package dev.southstar21.vectra.features.transit.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.time.formatBudapestHm
import dev.southstar21.vectra.features.transit.DirectionView
import dev.southstar21.vectra.features.transit.LiveSignalIcon
import dev.southstar21.vectra.features.transit.data.Arrival
import dev.southstar21.vectra.features.transit.etaMinutes
import dev.southstar21.vectra.features.transit.monitor
import dev.southstar21.vectra.features.transit.strip.OtherDirectionNote
import dev.southstar21.vectra.features.transit.strip.StripDirection
import dev.southstar21.vectra.features.transit.strip.drawnDirections
import dev.southstar21.vectra.features.transit.strip.toStripDirection

/**
 * The tracker sheet's departures: the clock time of each coming departure, both directions side by
 * side (the strip's order: the way the line draws right-heading on the right). Nothing when no
 * direction has a departure.
 */
@Composable
internal fun DeparturesSection(directions: List<DirectionView>, now: Long, modifier: Modifier = Modifier) {
    val drawn = remember(directions) { drawnDirections(directions.map { it.toStripDirection() }).filter { it.arrival != null } }
    if (drawn.isEmpty()) return
    val c = Vectra.colors
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            VText("Next departures", Modifier.weight(1f), vtext(20.sp to 24.sp, Weight.Bold), c.textPrimary, maxLines = 1)
            VText("${formatBudapestHm(now)} now", style = vtext(13.sp to 16.sp, Weight.Medium, mono = true).tnum(), color = c.textMuted)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 10.dp)) {
            // The strip draws the first direction on the right and the second on the left.
            val left = drawn.getOrNull(1)
            val right = drawn[0]
            // The note takes a line of its own: every column keeps it, so the departures line up.
            val reserve = drawn.any { it.otherDirection }
            if (left != null) {
                DepartureColumn(left, now, right = false, reserve, Modifier.weight(1f))
                Box(Modifier.padding(horizontal = 4.dp).fillMaxHeight().width(1.dp).background(c.textPrimary.copy(alpha = 0.08f)))
            }
            DepartureColumn(right, now, right = left != null, reserve, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DepartureColumn(d: StripDirection, now: Long, right: Boolean, reserveNote: Boolean, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    val side = if (right) Alignment.End else Alignment.Start
    val all = listOfNotNull(d.arrival) + d.later
    Column(modifier, horizontalAlignment = side, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp), horizontalAlignment = side) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!right) VIcon(RemixIcons.ArrowLeftLine, size = 13.dp, tint = c.textMuted)
                VText(d.destination, Modifier.weight(1f, fill = false), vtext(12.sp to 16.sp, Weight.Semibold), c.textSecondary, maxLines = 2, align = if (right) TextAlign.End else TextAlign.Start)
                if (right) VIcon(RemixIcons.ArrowRightLine, size = 13.dp, tint = c.textMuted)
            }
            if (d.otherDirection) OtherDirectionNote(align = if (right) TextAlign.End else TextAlign.Start)
            // Same line height as the note in sp, so the columns stay aligned at any font scale.
            else if (reserveNote) Spacer(Modifier.height(with(LocalDensity.current) { 15.sp.toDp() }))
        }
        all.forEachIndexed { i, a -> DepartureRow(a, i == 0, now, side) }
    }
}

/** One departure: its clock time, then how long until it and whether the time is live. */
@Composable
private fun DepartureRow(a: Arrival, next: Boolean, now: Long, side: Alignment.Horizontal) {
    val c = Vectra.colors
    val minutes = etaMinutes(a.arrivalMs, now)
    val color = when {
        !next -> c.textSecondary
        a.isPredicted && minutes <= 1 -> c.tints.emerald.text
        a.isPredicted -> Vectra.monitor.live
        else -> Vectra.monitor.sched
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().then(if (next) Modifier.background(Vectra.monitor.live.copy(alpha = 0.08f), shape) else Modifier).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = side,
    ) {
        VText(formatBudapestHm(a.arrivalMs), style = vtext(20.sp to 24.sp, Weight.Extrabold, mono = true).tnum(), color = color, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (a.isPredicted) LiveSignalIcon(size = 11.dp, tint = Vectra.monitor.live)
            VText(if (minutes <= 0) "now" else "in $minutes min", style = vtext(12.sp to 16.sp, Weight.Medium), color = c.textMuted, maxLines = 1)
        }
    }
}
