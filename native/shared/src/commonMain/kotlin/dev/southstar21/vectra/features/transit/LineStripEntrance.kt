package dev.southstar21.vectra.features.transit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import kotlinx.coroutines.delay
import kotlin.math.pow

/*
 * A tracker card's entrance (TC5 "skeleton to live"): while its first arrivals load, the strip is a
 * quiet grey line with placeholder vehicles and pills where the times go; when they land, the
 * route colour washes in and the placeholders spring to the real vehicles and their times.
 */

/** Seconds the entrance runs once the data lands; past it the strip is simply live. */
private const val EntranceSeconds = 1.05f

/** Where the placeholders sit (stop units on the seven-stop strip): one each side of your stop. */
internal const val GhostLeft = 1.6f
internal const val GhostRight = 4.4f

/** The cards below start a little later, so a list of them lands top to bottom. */
private const val StaggerMs = 50L

/** Placeholder grey: quiet on the card in both themes. */
internal val GhostInk = Palette.Slate400

/**
 * One strip's entrance. [skeleton] while its first arrivals load; then [seconds] runs from 0 to
 * [EntranceSeconds]. A strip that already had data when it appeared never plays it.
 */
@Stable
internal class StripEntrance(loading: Boolean) {
    var skeleton by mutableStateOf(loading)
        internal set
    internal val clock = Animatable(if (loading) 0f else EntranceSeconds)

    /** Seconds since the data landed (read in draw: it changes every frame). */
    val seconds: Float get() = clock.value

    val playing: Boolean get() = skeleton || clock.value < EntranceSeconds
}

/**
 * The entrance of a strip whose data is [loading]: plays once, when that first load lands, after
 * [index] × 50 ms. With reduced motion the strip switches straight to live.
 */
@Composable
internal fun rememberStripEntrance(loading: Boolean, index: Int): StripEntrance {
    val entrance = remember { StripEntrance(loading) }
    val reduce = Vectra.reduceMotion
    LaunchedEffect(loading) {
        if (loading) return@LaunchedEffect
        if (!entrance.skeleton) return@LaunchedEffect
        entrance.skeleton = false
        if (reduce) {
            entrance.clock.snapTo(EntranceSeconds)
        } else {
            delay(index.coerceIn(0, 6) * StaggerMs)
            entrance.clock.animateTo(EntranceSeconds, tween((EntranceSeconds * 1000).toInt(), easing = LinearEasing))
        }
    }
    return entrance
}

/** The route colour washing in over the grey. */
internal fun entranceTint(d: Float): Float = smooth(d, 0f, 0.35f)

/** How far the [i]th vehicle has sprung from its placeholder (0..1). */
private fun springOf(d: Float, i: Int): Float = ((d - 0.08f - i * 0.05f) / 0.5f).coerceIn(0f, 1f)

/** The [i]th vehicle's time fading in once it has nearly landed. */
internal fun entranceLabelAlpha(d: Float, i: Int): Float = ((d - 0.35f - i * 0.05f) / 0.25f).coerceIn(0f, 1f)

/** The placeholder pills fading away. */
internal fun pillAlpha(d: Float): Float = 1f - smooth(d, 0.3f, 0.5f)

/** A vehicle mid-entrance: where it is, its size and opacity, and how much of its grey placeholder still shows. */
internal class EntranceVehicle(val position: Float, val scale: Float, val alpha: Float, val ghost: Float)

/** The [i]th vehicle (heading [heading], landing at [position]) at [d] seconds into the entrance. */
internal fun entranceVehicle(d: Float, i: Int, heading: Int, position: Float): EntranceVehicle {
    val m = springOf(d, i)
    val from = if (heading > 0) GhostLeft else GhostRight
    return EntranceVehicle(
        position = from + (position - from) * backOut(m, 1.2f),
        scale = 0.6f + 0.4f * backOut(m, 1.70158f),
        alpha = smooth(m, 0f, 0.3f),
        ghost = 1f - smooth(m, 0f, 0.4f),
    )
}

/** Ease-out that overshoots by [s] before settling: a spring's feel in a fixed time. */
private fun backOut(u: Float, s: Float): Float {
    if (u <= 0f) return 0f
    val v = u.coerceAtMost(1f) - 1
    return 1 + (s + 1) * v.pow(3) + s * v.pow(2)
}
