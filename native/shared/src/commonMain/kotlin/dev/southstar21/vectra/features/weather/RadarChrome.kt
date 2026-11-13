package dev.southstar21.vectra.features.weather

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateContentSize
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.util.roundHalfUpToInt
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.consumeTouches
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import kotlin.math.floor

private val ChromeShape = RoundedCornerShape(24.dp)

/** Light to heavy, in the radar's own colours. */
private val RainRamp = listOf(Color(0xFF2DB52A), Color(0xFF41F532), Color(0xFFFACC15), Palette.Red500, Color(0xFFD946EF))

/**
 * The radar's top island, three pills in a row: the weather now (sky and temperature), whether the
 * map shows live rain or an earlier [frame], and close.
 */
@Composable
internal fun RadarHeader(weather: WeatherData?, frame: RadarFrameLabel?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    // Equal slots either side keep the middle pill centred.
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { if (weather != null) NowPill(weather) }
        LiveChip(frame)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .size(PillHeight)
                    .glass(CircleShape)
                    .tappable(CircleShape, role = Role.Button, onClickLabel = "Close radar", onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { VIcon(RemixIcons.CloseLine, size = 20.dp, tint = c.textPrimary, contentDescription = "Close radar") }
        }
    }
}

/** The sky's icon and the temperature. */
@Composable
private fun NowPill(weather: WeatherData) {
    val c = Vectra.colors
    val meta = weatherCodeMeta(weather.weatherCode)
    val (tint, alpha) = skyTone(meta.sky, c)
    Row(
        Modifier.height(PillHeight).glass(CircleShape).consumeTouches().padding(start = 12.dp, end = 14.dp)
            .semantics { contentDescription = "${roundHalfUpToInt(weather.temperature)} degrees, ${meta.label}" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(skyArt(meta.sky), Modifier.graphicsLayer { this.alpha = alpha }, size = 22.dp, tint = tint)
        VText(
            "${roundHalfUpToInt(weather.temperature)}°",
            style = vtext(TypeScale.BodyLarge, Weight.Bold).copy(fontFeatureSettings = "tnum"),
            color = c.textPrimary,
        )
    }
}

private val PillHeight = 44.dp

/** "-80m" → "1 h 20 min ago"; "-120m" → "2 h ago". */
internal fun agoText(relative: String): String {
    val mins = relative.removePrefix("-").removeSuffix("m").toIntOrNull() ?: return relative
    val h = mins / 60
    val m = mins % 60
    return when {
        h == 0 -> "$m min ago"
        m == 0 -> "$h h ago"
        else -> "$h h $m min ago"
    }
}

/** "Live" with a pulsing dot on the newest frame; on an earlier one its time and how long ago, dot still. */
@Composable
private fun LiveChip(frame: RadarFrameLabel?) {
    val c = Vectra.colors
    val live = frame == null || frame.relative == "Now"
    Row(
        Modifier.height(PillHeight).glass(CircleShape).padding(horizontal = 14.dp).animateContentSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) {
            LiveDot()
            VText("Live", style = vtext(TypeScale.Caption, Weight.Bold), color = c.tints.emerald.text)
        } else {
            Box(Modifier.size(8.dp).background(c.textMuted, CircleShape))
            VText(
                "${frame.timeStr} · ${agoText(frame.relative)}",
                style = vtext(TypeScale.Caption, Weight.Bold),
                color = c.textSecondary,
            )
        }
    }
}

/** What the timeline card shows: the frames' rain at your spot, which one is on, and whether it plays. */
internal class TimelineState(
    val rain: List<RainSample?>,
    val index: Int,
    val label: RadarFrameLabel?,
    val playing: Boolean,
    val enabled: Boolean,
)

/**
 * The bottom card: play, the frame's time (a live dot on Now), "Rain here", and a bar per 10-minute
 * step showing the rain at your spot, which is also the scrubber.
 */
@Composable
internal fun RadarTimeline(state: TimelineState, onTogglePlay: () -> Unit, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    Column(modifier.fillMaxWidth().glass(ChromeShape).consumeTouches().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(c.accentPrimary, CircleShape)
                    .tappable(CircleShape, enabled = state.enabled, role = Role.Button, haptic = { tick() }, onClick = onTogglePlay)
                    .semantics { contentDescription = if (state.playing) "Pause radar animation" else "Play radar animation" },
                contentAlignment = Alignment.Center,
            ) {
                if (state.playing) VIcon(RemixIcons.PauseFill, size = 18.dp, tint = Color.White)
                else VIcon(RemixIcons.PlayFill, Modifier.padding(start = 2.dp), size = 18.dp, tint = Color.White)
            }
            FrameTime(state.label, Modifier.weight(1f))
            RainHere(state.rain.getOrNull(state.index))
        }
        RainBars(state, onPick, Modifier.padding(top = 10.dp).fillMaxWidth().height(36.dp))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            VText("−2 h", Modifier.weight(1f), vtext(10.sp to 14.sp, Weight.Medium, mono = true), c.textMuted)
            VText("Now", style = vtext(10.sp to 14.sp, Weight.Medium, mono = true), color = c.textMuted)
        }
    }
}

@Composable
private fun FrameTime(label: RadarFrameLabel?, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        VText(label?.timeStr ?: "--:--", style = vtext(TypeScale.Subtitle, Weight.Bold, mono = true), color = c.textPrimary)
        val now = label?.relative == "Now"
        VText(
            when {
                label == null -> ""
                now -> "Now"
                else -> label.relative.replace("-", "−").replace("m", " min")
            },
            style = vtext(TypeScale.Caption, Weight.Semibold),
            color = if (now) c.tints.emerald.text else c.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun LiveDot() {
    val reduce = Vectra.reduceMotion
    val ping = rememberInfiniteTransition(label = "live")
    val t by ping.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "liveT")
    Box(Modifier.size(8.dp), contentAlignment = Alignment.Center) {
        if (!reduce) {
            Box(Modifier.size(8.dp).graphicsLayer { val s = 1f + t * 1.4f; scaleX = s; scaleY = s; alpha = 0.5f * (1f - t) }.background(Palette.Emerald500, CircleShape))
        }
        Box(Modifier.size(8.dp).background(Palette.Emerald500, CircleShape))
    }
}

/** "Rain here" over the frame's reading, with the light-to-heavy ramp as its key. */
@Composable
private fun RainHere(sample: RainSample?) {
    val c = Vectra.colors
    Column(horizontalAlignment = Alignment.End) {
        VText(rainLabel(sample), style = vtext(TypeScale.Caption, Weight.Bold), color = c.textPrimary, maxLines = 1)
        Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            VText("Light", style = vtext(9.sp to 12.sp, Weight.Medium), color = c.textMuted)
            Box(Modifier.width(36.dp).height(4.dp).background(Brush.horizontalGradient(RainRamp), CircleShape))
            VText("Heavy", style = vtext(9.sp to 12.sp, Weight.Medium), color = c.textMuted)
        }
    }
}

/** A bar's ink: the rain's own colour, or grey when dry (fainter while still loading); the picked one full. */
private fun barColor(s: RainSample?, selected: Boolean, muted: Color): Color = when {
    s == null -> muted.copy(alpha = 0.25f)
    s.level <= 0f -> muted.copy(alpha = if (selected) 0.8f else 0.4f)
    else -> s.color.copy(alpha = if (selected) 1f else 0.6f)
}

/** One bar per frame, as tall as the rain at your spot; tap or drag along them to pick a frame. */
@Composable
private fun RainBars(state: TimelineState, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    val count = state.rain.size
    val haptics = Vectra.haptics
    val current by rememberUpdatedState(state.index)
    // A tick each time the pick lands on another frame, the way a picker's detents feel.
    fun pick(x: Float, width: Int, dragging: Boolean) {
        if (count == 0 || width <= 0 || !state.enabled) return
        val i = floor(x / width * count).toInt().coerceIn(0, count - 1)
        if (i == current) return
        if (dragging) haptics.frequentTick() else haptics.tick()
        onPick(i)
    }
    val muted = c.textMuted
    val accent = c.accentPrimary
    Canvas(
        modifier
            .semantics { contentDescription = "Radar timeline: frame ${state.index + 1} of $count" }
            .pointerInput(count, state.enabled) { detectTapGestures { pick(it.x, size.width, dragging = false) } }
            .pointerInput(count, state.enabled) {
                detectHorizontalDragGestures { change, _ -> change.consume(); pick(change.position.x, size.width, dragging = true) }
            },
    ) {
        if (count == 0) return@Canvas
        val slot = size.width / count
        val gap = 3.dp.toPx()
        val w = slot - gap
        val floor = 3.dp.toPx()
        val maxH = size.height - 6.dp.toPx()
        for (i in 0 until count) {
            val s = state.rain[i]
            val selected = i == state.index
            val h = floor + (maxH - floor) * (s?.level ?: 0f)
            val color = barColor(s, selected, muted)
            val left = i * slot + gap / 2
            drawRoundRect(color, Offset(left, maxH - h), Size(w, h), CornerRadius(w / 2))
            if (selected) drawCircle(accent, 2.5.dp.toPx(), Offset(left + w / 2, size.height - 2.dp.toPx()))
        }
    }
}
