package dev.southstar21.vectra.features.transit

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import dev.southstar21.vectra.core.designsystem.theme.backOut
import dev.southstar21.vectra.core.designsystem.theme.smoothstep
import kotlin.math.min
import kotlin.math.pow

/**
 * A route map's entrance as its loading orb melts away ([MapLoadingOrb] drives [progress]; the
 * [RouteMap] follows it). Once the orb's dots reach the line, the line fades in under them, the
 * stops pop in from your stop outwards, then your location, the vehicles and the map controls.
 * Read [progress] in draw or layout where possible: it changes every frame of the melt.
 */
@Stable
class MapReveal {
    /** 0 while the map is still covered, 1 once everything is in (and for a map that never melted). */
    var progress by mutableFloatStateOf(1f)
        internal set

    /** The map's real route layers are on (they take over from the look-alikes near the end). */
    var layersOn by mutableStateOf(true)
        internal set

    /** The map has drawn its real layers: the look-alikes fade out. */
    var done by mutableStateOf(true)
        internal set

    /**
     * The map's look-alike stops and location (set by its [RouteReveal]). While the loading orb
     * melts it draws them over its own dots, so the stops always sit on top of the line.
     */
    internal var drawMarks: (DrawScope.(p: Float) -> Unit)? = null

    /** The melting orb is drawing [drawMarks] (the map's look-alikes leave them out meanwhile). */
    internal var orbDrawsMarks by mutableStateOf(false)

    internal fun cover() {
        progress = 0f
        layersOn = false
        done = false
    }

    internal fun advance(p: Float) {
        progress = p
        if (p >= RevealHandOver) layersOn = true
    }
}

internal fun revealLine(p: Float): Float = smoothstep(0.62f, 0.9f, p)

/** A stop's pop; [order] 0 is your stop, 1 the farthest. */
internal fun revealStop(p: Float, order: Float): Float = backOut(((p - 0.55f - order * 0.25f) / 0.2f).coerceIn(0f, 1f))

internal fun revealLocation(p: Float): Float = backOut(((p - 0.75f) / 0.2f).coerceIn(0f, 1f))

/** The [index]th vehicle's pop (a slight stagger between the first few). */
internal fun revealVehicle(p: Float, index: Int): Float = backOut(((p - 0.78f - min(index, 2) * 0.02f) / 0.18f).coerceIn(0f, 1f))

internal fun revealControls(p: Float): Float = smoothstep(0.55f, 0.95f, p)

