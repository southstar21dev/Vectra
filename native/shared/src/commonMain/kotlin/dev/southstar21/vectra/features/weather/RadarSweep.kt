package dev.southstar21.vectra.features.weather

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.features.transit.OrbEngine
import dev.southstar21.vectra.features.transit.ThinkingOrbState
import dev.southstar21.vectra.features.transit.drawOrb
import dev.southstar21.vectra.features.transit.grey
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * M1 "single sweep line": the loading orb sits on your spot; once the radar is in, its dots line up
 * into one line pointing up from you that turns once around you, uncovering the rain behind it,
 * then fades. The rain it uncovers is the radar's own copy of the live frame ([PlacedTile]); the
 * map's layer takes over from it, pixel for pixel, once the turn is done.
 */

/** Dots gathering into the line (and the cover lifting). */
private const val GatherMs = 380f
/** One turn around you. */
private const val TurnMs = 1300f
/** The line fading at the top while the last of the rain behind it fades in. */
private const val SettleMs = 320f
/** How far behind the line the rain takes to fade fully in, in degrees. */
private const val FeatherDeg = 70f
private const val SweepMs = GatherMs + TurnMs + SettleMs
/** Reduced motion, or no rain in view: the cover simply fades. */
private const val PlainFadeMs = 300f
/** The map layer's opacity, which the drawn tiles match. */
internal const val RadarOpacity = 0.85f

private val OrbDp = 64.dp
private const val OrbScale = 1.15f
private val DotOnLine = 1.3.dp
/** The soft glow along the line. */
private val GlowWidth = 10.dp

internal enum class SweepPhase { Loading, Sweeping, HandingOver, Done }

/** The sweep's phase and frame clock. Frames are read in draw only, so they never recompose. */
@Stable
internal class RadarSweep(reduced: Boolean) {
    val engine = OrbEngine(ThinkingOrbState.Searching, OrbDp.value.toDouble())
    private val share = FloatArray(engine.count)
    private val delay = FloatArray(engine.count)
    private val reducedMotion = reduced
    var phase by mutableStateOf(SweepPhase.Loading)
        private set
    private var frame by mutableLongStateOf(0L)
    private var start = 0L

    /** The drawn rain's opacity: dropped once the map's layer shows the same pixels. */
    var overlay by mutableFloatStateOf(1f)
        private set

    /**
     * Ticks the orb until [isReady], plays the sweep, then [handOver]s to the map (which returns once
     * its layer has drawn) and drops the drawn rain.
     */
    suspend fun run(isReady: () -> Boolean, hasRain: () -> Boolean, handOver: suspend () -> Unit) {
        if (phase == SweepPhase.Done) return
        while (!isReady()) withFrameNanos { if (!reducedMotion) frame = it }
        // No rain in view: nothing to sweep over, so the orb just fades away.
        plain = reducedMotion || !hasRain()
        phase = SweepPhase.Sweeping
        val span = if (plain) PlainFadeMs else SweepMs
        var done = false
        while (!done) {
            withFrameNanos { n ->
                if (start == 0L) { start = n; prepare() }
                frame = n
                done = (n - start) / 1e6f >= span
            }
        }
        phase = SweepPhase.HandingOver
        handOver()
        overlay = 0f
        phase = SweepPhase.Done
    }

    /** Skips the whole thing (an error, or nothing to show). */
    fun finish() {
        overlay = 0f
        phase = SweepPhase.Done
    }

    /** Milliseconds into the sweep (0 before it starts). */
    fun elapsed(): Float = if (start == 0L) 0f else (frame - start) / 1e6f

    fun orbTime(): Double = if (reducedMotion || frame == 0L) 0.6 else frame / 1e9 * engine.speed

    /** Dots nearest the orb's top go furthest out along the line. */
    private fun prepare() {
        engine.compute(orbTime())
        val n = engine.count
        val byY = (0 until n).sortedByDescending { engine.y[it] }
        byY.forEachIndexed { rank, i -> share[i] = 0.04f + 0.96f * rank / max(1, n - 1) }
        for (i in 0 until n) delay[i] = hash(i) * 0.25f
    }

    fun share(i: Int) = share[i]
    fun delay(i: Int) = delay[i]
    /** Fading rather than sweeping (reduced motion, or no rain to uncover). */
    var plain = reduced
        private set
}

/** What the sweep draws with: the cover, the orb's ink, the line's ink and the live frame's tiles (dp). */
internal class SweepInk(val cover: Color, val dark: Boolean, val line: Color)

/**
 * The sweep over the map: the cover with the orb (at [orbAt], px) while loading, then the line and
 * the rain it uncovers, centred on [pivot] (px; your spot). [tiles] are in dp.
 */
internal fun DrawScope.drawRadarSweep(sweep: RadarSweep, ink: SweepInk, orbAt: Offset, pivot: Offset, tiles: List<PlacedTile>) {
    val t = sweep.elapsed()
    val k = OrbScale * density
    when (sweep.phase) {
        SweepPhase.Loading -> {
            drawRect(ink.cover)
            sweep.engine.compute(sweep.orbTime())
            drawOrb(sweep.engine, orbAt, k, ink.dark)
        }
        SweepPhase.Sweeping -> if (sweep.plain) {
            val p = (t / PlainFadeMs).coerceIn(0f, 1f)
            drawTiles(tiles, RadarOpacity * p)
            drawRect(ink.cover.copy(alpha = 1 - p))
            drawOrb(sweep.engine, orbAt, k, ink.dark, alpha = 1 - p)
        } else {
            drawRect(ink.cover.copy(alpha = 1 - smooth(t, 0f, GatherMs * 0.8f)))
            val turn = ((t - GatherMs) / TurnMs).coerceIn(0f, 1f)
            val angle = 360f * easeInOutSine(turn)
            // Once the line is back at the top, the feather behind it catches up on its own.
            val settle = ((t - GatherMs - TurnMs) / SettleMs).coerceIn(0f, 1f)
            val head = angle + FeatherDeg * (1 - (1 - settle) * (1 - settle))
            when {
                settle >= 1f -> drawTiles(tiles, RadarOpacity)
                head > 0f -> drawFeathered(tiles, pivot, head)
            }
            drawLine(sweep, ink, orbAt, pivot, t, angle, k)
        }
        SweepPhase.HandingOver -> drawTiles(tiles, RadarOpacity * sweep.overlay)
        SweepPhase.Done -> Unit
    }
}

/** The orb's dots gathering into the line from [pivot], then the line turning by [angle] and fading. */
private fun DrawScope.drawLine(sweep: RadarSweep, ink: SweepInk, orbAt: Offset, pivot: Offset, t: Float, angle: Float, k: Float) {
    // Fades as it comes back round to the top.
    val fade = 1 - smooth(t, GatherMs + TurnMs * 0.85f, SweepMs)
    if (fade <= 0f) return
    val engine = sweep.engine
    engine.compute(sweep.orbTime())
    val reach = hypot(max(pivot.x, size.width - pivot.x), max(pivot.y, size.height - pivot.y))
    val length = reach * smooth(t, GatherMs * 0.35f, GatherMs + TurnMs * 0.15f)
    val rad = (angle - 90f) * PI.toFloat() / 180f
    val dir = Offset(cos(rad), sin(rad))
    val half = engine.size / 2
    val onLine = DotOnLine.toPx()
    val gathered = smooth(t, GatherMs * 0.6f, GatherMs)
    if (gathered > 0f && length > 0f) {
        drawLine(
            Brush.linearGradient(listOf(ink.line.copy(alpha = 0.16f * gathered * fade), Color.Transparent), pivot, pivot + dir * length),
            pivot,
            pivot + dir * length,
            strokeWidth = GlowWidth.toPx(),
            cap = StrokeCap.Round,
        )
    }
    for (o in 0 until engine.count) {
        val i = engine.order[o]
        val e = easeInOut(((t / GatherMs - sweep.delay(i)) / 0.75f).coerceIn(0f, 1f))
        val from = Offset(orbAt.x + ((engine.x[i] - half) * k).toFloat(), orbAt.y + ((engine.y[i] - half) * k).toFloat())
        val to = pivot + dir * (sweep.share(i) * length)
        val g = engine.grey(i, ink.dark)
        val color = lerp(Color(g, g, g), ink.line, e)
        // Brightest at you, thinning out towards the tip.
        val taper = 1f - 0.8f * sweep.share(i) * e
        val a = (engine.a[i].toFloat() + (1 - engine.a[i].toFloat()) * e) * fade * taper
        if (a < 0.02f) continue
        val r0 = (max(engine.rMin, engine.r[i]) * k).toFloat()
        drawCircle(color.copy(alpha = a), r0 + (onLine - r0) * e, from + (to - from) * e)
    }
}

/** The live frame's tiles, at the map layer's place and size. */
private fun DrawScope.drawTiles(tiles: List<PlacedTile>, alpha: Float) {
    if (alpha <= 0f) return
    val side = (RadarTilePx * density).roundToInt()
    for (tile in tiles) {
        drawImage(
            tile.image,
            dstOffset = IntOffset((tile.topLeft.x * density).roundToInt(), (tile.topLeft.y * density).roundToInt()),
            dstSize = IntSize(side, side),
            alpha = alpha,
            filterQuality = FilterQuality.Low,
        )
    }
}

/**
 * The rain the line has passed: full well behind it, fading in over the [FeatherDeg] just behind
 * the head at [head] degrees clockwise from straight up, none ahead of it.
 */
private fun DrawScope.drawFeathered(tiles: List<PlacedTile>, pivot: Offset, head: Float) {
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(Offset.Zero, size), Paint())
    drawTiles(tiles, RadarOpacity)
    val stops = listOf(0f, head - FeatherDeg, head, 360f)
        .map { it.coerceIn(0f, 360f) }
        .distinct()
        .sorted()
        .map { a -> (a / 360f) to Color.Black.copy(alpha = ((head - a) / FeatherDeg).coerceIn(0f, 1f)) }
    val radius = hypot(size.width, size.height)
    // A sweep gradient starts at 3 o'clock; turned back a quarter it starts straight up.
    rotate(-90f, pivot) {
        drawRect(
            Brush.sweepGradient(*stops.toTypedArray(), center = pivot),
            topLeft = pivot - Offset(radius, radius),
            size = Size(radius * 2, radius * 2),
            blendMode = BlendMode.DstIn,
        )
    }
    canvas.restore()
}

private fun smooth(x: Float, a: Float, b: Float): Float {
    val u = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return u * u * (3 - 2 * u)
}

private fun easeInOutSine(u: Float): Float = -(cos(PI.toFloat() * u) - 1) / 2

private fun easeInOut(u: Float): Float = if (u < 0.5f) 4 * u * u * u else 1 - (-2 * u + 2).pow(3) / 2

private fun hash(i: Int): Float {
    val h = sin(i * 12.9898 + 4.1 * 78.233) * 43758.5453
    return (h - floor(h)).toFloat()
}
