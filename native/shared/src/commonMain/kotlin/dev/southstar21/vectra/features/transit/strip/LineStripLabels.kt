package dev.southstar21.vectra.features.transit.strip

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
import dev.southstar21.vectra.features.transit.LiveSignalIcon
import dev.southstar21.vectra.features.transit.monitor

private val LabelW = 120.dp

/** The least room between two times on the row. */
private val LabelGap = 64.dp

/**
 * Where each vehicle's time goes along the row (strip units): over its marker, except that two
 * closer than [LabelGap] are pushed apart evenly, and none past the strip's ends.
 */
internal fun spreadLabels(positions: List<Float>, span: Int, width: Dp): List<Float> {
    if (positions.size < 2 || width <= 0.dp) return positions
    val gap = LabelGap / width * span
    val order = positions.indices.sortedBy { positions[it] }
    val out = positions.toFloatArray()
    // Twice over: a push can bring a pair further along too close.
    repeat(2) {
        for (k in 1 until order.size) {
            val a = order[k - 1]
            val b = order[k]
            val short = gap - (out[b] - out[a])
            if (short > 0f) {
                out[a] -= short / 2
                out[b] += short / 2
            }
        }
    }
    return out.map { it.coerceIn(0f, span.toFloat()) }
}

/** A vehicle's time over its marker: centred, or held in from the strip's ends. All sit on one row above the line. */
@Composable
internal fun PlacedLabel(v: StripVehicle, x: Dp, frac: Float, mid: Dp) {
    val (left, align) = when {
        frac < 0.1f -> (x - 8.dp) to Alignment.Start
        frac > 0.9f -> (x - LabelW + 8.dp) to Alignment.End
        else -> (x - LabelW / 2) to Alignment.CenterHorizontally
    }
    val boxAlign = when (align) {
        Alignment.Start -> Alignment.BottomStart
        Alignment.End -> Alignment.BottomEnd
        else -> Alignment.BottomCenter
    }
    Box(Modifier.offset(x = left).width(LabelW).height(mid - 14.dp), contentAlignment = boxAlign) { VehicleLabel(v, align) }
}

/** How a departure's minutes read: the one rule every transit list follows. */
internal enum class TimeTone { Arriving, Live, Scheduled, Faded }

/**
 * Green when it is (nearly) here, the board's amber when it is a live prediction, its grey when it
 * is only the timetable. [label] is the minutes as printed ("NOW", "1'"); out-of-date data fades.
 */
internal fun timeTone(kind: StripTimeKind, arriving: Boolean, label: String?, faded: Boolean = false): TimeTone = when {
    faded -> TimeTone.Faded
    arriving || label == "NOW" || (kind == StripTimeKind.Live && label == "1'") -> TimeTone.Arriving
    kind == StripTimeKind.Scheduled -> TimeTone.Scheduled
    else -> TimeTone.Live
}

@Composable
internal fun timeToneColor(tone: TimeTone): Color = when (tone) {
    TimeTone.Faded -> Vectra.colors.textMuted
    TimeTone.Arriving -> Vectra.colors.tints.emerald.text
    TimeTone.Scheduled -> Vectra.monitor.sched
    TimeTone.Live -> Vectra.monitor.live
}

/** The colour of a departure's minutes by the one rule, from whether it is live and the printed [label]. */
@Composable
internal fun departureColor(isPredicted: Boolean, label: String): Color =
    timeToneColor(timeTone(if (isPredicted) StripTimeKind.Live else StripTimeKind.Scheduled, arriving = false, label = label))

/** A vehicle's time colour: green when it is (nearly) here, the board's ink otherwise. */
@Composable
internal fun stripTimeColor(v: StripVehicle): Color = timeToneColor(timeTone(v.kind, v.arriving, v.label, v.faded))

/** Live signal, timetable clock, or nothing for faded (out-of-date) vehicles. */
@Composable
private fun TimeIcon(v: StripVehicle) {
    val c = Vectra.colors
    when {
        v.faded -> Unit
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
                VText(v.label, style = vtext(16.sp to 20.sp, Weight.Extrabold, mono = true).tnum(), color = stripTimeColor(v), maxLines = 1, softWrap = false)
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
        if (v.showMarker) { sub(); main() } else { main(); sub() }
    }
}

private data class AlignedEndData(
    val destination: String,
    val arrowRight: Boolean,
    val bus: Boolean,
    val vehicle: StripVehicle?,
    val alignEnd: Boolean,
)

/**
 * Aligned direction headers above the strip: left side matches the vehicle approaching from the
 * left (heading right →), right side matches the vehicle approaching from the right (heading left ←).
 * Shows each direction's destination and primary ETA once, with zero duplication.
 */
@Composable
internal fun AlignedStripHeader(model: StripModel, modifier: Modifier = Modifier) {
    val leftSide = model.rightEnd?.let {
        AlignedEndData(
            destination = it,
            arrowRight = true,
            bus = model.rightBus,
            vehicle = model.vehicles.firstOrNull { v -> v.heading > 0 },
            alignEnd = false,
        )
    }
    val rightSide = model.leftEnd?.let {
        AlignedEndData(
            destination = it,
            arrowRight = false,
            bus = model.leftBus,
            vehicle = model.vehicles.firstOrNull { v -> v.heading < 0 },
            alignEnd = true,
        )
    }
    if (leftSide == null && rightSide == null) return
    Row(
        modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (leftSide != null) AlignedEndColumn(leftSide, Modifier.weight(1f))
        if (rightSide != null) AlignedEndColumn(rightSide, Modifier.weight(1f))
    }
}

@Composable
private fun AlignedEndColumn(data: AlignedEndData, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    val align = if (data.alignEnd) Alignment.End else Alignment.Start
    Column(modifier, horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!data.arrowRight) VIcon(RemixIcons.ArrowLeftLine, size = 13.dp, tint = directionArrowTint(c))
            if (data.bus) VIcon(RemixIcons.BusLine, size = 13.dp, tint = BusBlue)
            VText(
                data.destination,
                Modifier.weight(1f, fill = false),
                style = vtext(12.5.sp to 16.sp, Weight.Semibold),
                color = c.textPrimary,
                maxLines = 2,
                align = if (data.alignEnd) TextAlign.End else TextAlign.Start,
            )
            if (data.arrowRight) VIcon(RemixIcons.ArrowRightLine, size = 13.dp, tint = directionArrowTint(c))
        }
        val v = data.vehicle
        if (v?.label != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (data.alignEnd && v.sub != null) {
                    VText(v.sub, style = vtext(11.sp to 15.sp, Weight.Medium), color = c.textMuted, maxLines = 1)
                }
                if (!data.bus || !v.replacement) TimeIcon(v)
                VText(
                    v.label,
                    style = vtext(16.sp to 20.sp, Weight.Extrabold, mono = true).tnum(),
                    color = stripTimeColor(v),
                    maxLines = 1,
                )
                if (!data.alignEnd && v.sub != null) {
                    VText(v.sub, style = vtext(11.sp to 15.sp, Weight.Medium), color = c.textMuted, maxLines = 1)
                }
            }
        }
    }
}
