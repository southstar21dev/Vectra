package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext

private val LabelW = 120.dp

/** A vehicle's time beside its marker: centred, or held in from the strip's ends. */
@Composable
internal fun PlacedLabel(v: StripVehicle, x: Dp, frac: Float, mid: Dp) {
    val above = v.heading > 0
    val (left, align) = when {
        frac < 0.1f -> (x - 8.dp) to Alignment.Start
        frac > 0.9f -> (x - LabelW + 8.dp) to Alignment.End
        else -> (x - LabelW / 2) to Alignment.CenterHorizontally
    }
    val boxAlign = when (align) {
        Alignment.Start -> if (above) Alignment.BottomStart else Alignment.TopStart
        Alignment.End -> if (above) Alignment.BottomEnd else Alignment.TopEnd
        else -> if (above) Alignment.BottomCenter else Alignment.TopCenter
    }
    Box(
        Modifier
            .offset(x = left, y = if (above) 0.dp else mid + 14.dp)
            .width(LabelW)
            .then(if (above) Modifier.height(mid - 14.dp) else Modifier),
        contentAlignment = boxAlign,
    ) { VehicleLabel(v, align) }
}

/** A vehicle's time colour: green when it is (nearly) here, the board's ink otherwise. */
@Composable
internal fun stripTimeColor(v: StripVehicle): Color {
    val c = Vectra.colors
    val ink = Vectra.monitor
    return when {
        v.faded -> c.textMuted
        v.second -> c.textSecondary
        v.arriving || (v.kind == StripTimeKind.Live && v.label == "1'") -> c.tints.emerald.text
        v.kind == StripTimeKind.Scheduled -> ink.sched
        else -> ink.live
    }
}

/** Live signal, timetable clock, or nothing for faded and second vehicles. */
@Composable
private fun TimeIcon(v: StripVehicle) {
    val c = Vectra.colors
    when {
        v.faded || v.second -> Unit
        v.replacement -> VIcon(RemixIcons.BusLine, size = 13.dp, tint = BusBlue)
        v.kind == StripTimeKind.Scheduled -> VIcon(RemixIcons.TimeLine, size = 12.dp, tint = Vectra.monitor.sched)
        else -> LiveSignalIcon(size = 11.dp, tint = if (v.metro) c.tints.purple.text else c.tints.emerald.text)
    }
}

@Composable
private fun VehicleLabel(v: StripVehicle, align: Alignment.Horizontal) {
    val c = Vectra.colors
    val main: @Composable () -> Unit = {
        if (v.label != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                TimeIcon(v)
                val size = if (v.second) 13.sp to 17.sp else 16.sp to 20.sp
                VText(v.label, style = vtext(size, Weight.Extrabold, mono = true).tnum(), color = stripTimeColor(v), maxLines = 1, softWrap = false)
            }
        }
    }
    val sub: @Composable () -> Unit = {
        if (v.sub != null) {
            VText(
                v.sub,
                Modifier.widthIn(max = 72.dp),
                vtext(11.sp to 15.sp, Weight.Semibold),
                c.textMuted,
                align = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
    Column(horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        // A far-away marker's stop count reads above its time; a time without a marker leads.
        if (v.heading > 0 && v.showMarker) { sub(); main() } else { main(); sub() }
    }
}

/**
 * The destinations under the strip: where left-heading and right-heading vehicles go. A fork
 * puts its second destination under the first at that end.
 */
@Composable
internal fun StripEnds(model: StripModel, modifier: Modifier = Modifier) {
    if (model.leftEnd == null && model.rightEnd == null) return
    val fork = model.fork
    val left = listOfNotNull(model.leftEnd, fork?.takeIf { it.heading < 0 }?.destination)
    val right = listOfNotNull(model.rightEnd, fork?.takeIf { it.heading > 0 }?.destination)
    Row(modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            left.forEachIndexed { i, name -> EndLabel(name, right = false, bus = i == 0 && model.leftBus) }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            right.forEachIndexed { i, name -> EndLabel(name, right = true, bus = i == 0 && model.rightBus) }
        }
    }
}

@Composable
private fun EndLabel(name: String, right: Boolean, bus: Boolean) {
    val c = Vectra.colors
    val style = vtext(12.sp to 16.sp, Weight.Semibold)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!right) VIcon(RemixIcons.ArrowLeftLine, size = 13.dp, tint = c.textMuted)
        // Replacement buses run this way: say so where they go.
        if (bus) VIcon(RemixIcons.BusLine, size = 13.dp, tint = BusBlue)
        VText(name, Modifier.weight(1f, fill = false), style = style, color = c.textSecondary, maxLines = 1, align = if (right) TextAlign.End else TextAlign.Start)
        if (right) VIcon(RemixIcons.ArrowRightLine, size = 13.dp, tint = c.textMuted)
    }
}
