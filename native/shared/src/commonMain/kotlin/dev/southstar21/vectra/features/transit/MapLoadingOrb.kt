package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.LocalPageActive
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.position
import kotlinx.coroutines.delay
import org.maplibre.compose.map.MapState
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/* A map's loading cover: the thinking orb, which melts into the map once it is ready. */

private const val MeltMs = 1350L
private const val FadeMs = 300L
private val OrbSize = 64.dp
private const val OrbScale = 1.15f
/** The orb sits a little above the middle, its label under it. */
private val OrbLift = 16.dp
private val LabelDrop = 41.dp
/** The settled dots' radius on the line. */
private val DotOnLine = 2.1.dp
/** How far a scattering dot bows off its straight path, at most. */
private val ArcMax = 26.dp
/** Share of the visible line left bare at each end. */
private const val LineInset = 0.02f

/** Into points: how far the dots' start times spread (× their 0–0.14 delay), and how long each takes to land. */
private const val PointsStagger = 2.6f
private const val PointsSpan = 0.6f

/** A dot landing on its point shrinks and fades over the last part of its flight. */
private fun landing(e: Float): Float = 1 - smooth(e, 0.6f, 1f)

private enum class CoverPhase { Loading, Melting, Gone }

/** The cover's phase and frame clock. Frames are read in draw only, so they never recompose. */
@Stable
private class OrbCover(loading: Boolean) {
    val engine = OrbEngine(ThinkingOrbState.Searching, OrbSize.value.toDouble())
    val plan = MeltPlan(engine.count)
    var phase by mutableStateOf(if (loading) CoverPhase.Loading else CoverPhase.Gone)
        private set
    private var frame by mutableLongStateOf(0L)
    private var meltStart by mutableLongStateOf(0L)

    /** Loading while [visible]; dropping it starts the melt. */
    fun follow(visible: Boolean) {
        if (visible) phase = CoverPhase.Loading
        else if (phase == CoverPhase.Loading) phase = CoverPhase.Melting
    }

    /**
     * Ticks frames until the melt ends, driving [reveal] along. A loading orb off screen (or with
     * reduced motion) holds one still frame; with reduced motion the map comes in whole.
     */
    suspend fun run(pageActive: Boolean, reduced: Boolean, reveal: MapReveal?) {
        if (phase == CoverPhase.Loading && (reduced || !pageActive)) return
        if (phase == CoverPhase.Melting) meltStart = 0L
        while (phase != CoverPhase.Gone) {
            withFrameNanos { nanos ->
                frame = nanos
                if (phase == CoverPhase.Melting) {
                    if (meltStart == 0L) {
                        meltStart = nanos
                        plan.prepare(engine)
                    }
                    reveal?.advance(if (reduced) 1f else progress(reduced))
                    if (nanos - meltStart >= span(reduced) * 1_000_000) {
                        reveal?.advance(1f)
                        phase = CoverPhase.Gone
                    }
                }
            }
        }
    }

    /** The orb's clock (a still frame at 0.6, as ThinkingOrb). */
    fun orbTime(reduced: Boolean): Double = if (reduced || frame == 0L) 0.6 else frame / 1e9 * engine.speed

    /** Melt progress 0..1 (0 while loading). */
    fun progress(reduced: Boolean): Float =
        if (phase != CoverPhase.Melting || meltStart == 0L) 0f
        else ((frame - meltStart) / (span(reduced) * 1e6)).toFloat().coerceIn(0f, 1f)

    private fun span(reduced: Boolean) = if (reduced) FadeMs else MeltMs
}

/**
 * A map's loading cover (the map surface with the globe thinking orb and [label]). When [show]
 * drops, the orb bursts and its dots arc out over every stretch of the route lines in view
 * ([routePath], in this box's px), taking on [routeColor] while the cover clears, then fade into
 * the real line; with no line on screen they spray outward instead. Reduced motion: a still orb and a 300 ms crossfade.
 *
 * While up it is the touch target, not the hidden map under it, so a drag reaches whatever
 * scrolls around the map. [maxDurationMs] caps how long it can stay up.
 */
@Composable
fun BoxScope.MapLoadingOrb(
    show: Boolean,
    modifier: Modifier = Modifier,
    label: String = "Loading map…",
    routeColor: Color? = null,
    routePath: () -> List<List<Offset>> = { emptyList() },
    /** The map's own entrance, played in step with the melt (see [MapReveal]). */
    reveal: MapReveal? = null,
    maxDurationMs: Long = 3000,
    /**
     * [routePath] is a set of points (stops) rather than lines: each dot is drawn into its point,
     * shrinking and fading as it lands, so the dots stream into the stops instead of piling up.
     */
    intoPoints: Boolean = false,
) {
    var expired by remember(show) { mutableStateOf(false) }
    LaunchedEffect(show, maxDurationMs) {
        if (show) {
            delay(maxDurationMs)
            expired = true
        }
    }
    val visible = show && !expired
    // Already loaded when first shown (a cached map): nothing to melt.
    val cover = remember { OrbCover(loading = visible) }
    cover.follow(visible)
    val reduced = Vectra.reduceMotion
    if (reveal != null) {
        // Hidden while covered; whole once the cover is gone. The melt's frames set what's between.
        val phase = cover.phase
        SideEffect {
            when (phase) {
                CoverPhase.Loading -> reveal.cover()
                CoverPhase.Gone -> reveal.advance(1f)
                CoverPhase.Melting -> Unit
            }
            reveal.orbDrawsMarks = phase == CoverPhase.Melting && !reduced
        }
    }
    if (cover.phase == CoverPhase.Gone) return

    val c = Vectra.colors
    val pageActive = LocalPageActive.current
    LaunchedEffect(cover.phase, pageActive, reduced) { cover.run(pageActive, reduced, reveal) }
    val density = LocalDensity.current.density
    val ink = OrbInk(c.surfaceSecondary, routeColor ?: c.accentPrimary, c.isDark, intoPoints)

    Box(
        modifier
            .matchParentSize()
            .pointerInput(Unit) {}
            .semantics { if (cover.phase == CoverPhase.Loading) contentDescription = label },
    ) {
        Canvas(Modifier.matchParentSize().clipToBounds()) { drawCover(cover, reduced, density, ink, routePath, reveal) }
        VText(
            label,
            Modifier
                .align(Alignment.Center)
                .offset(y = LabelDrop)
                .graphicsLayer {
                    val p = cover.progress(reduced)
                    alpha = if (reduced) 1 - p else 1 - smooth(p, 0f, 0.15f)
                },
            style = vtext(TypeScale.Caption, Weight.Medium),
            color = c.textSecondary,
        )
    }
}

private class OrbInk(val cover: Color, val route: Color, val dark: Boolean, val points: Boolean = false)

private fun DrawScope.drawCover(
    cover: OrbCover,
    reduced: Boolean,
    density: Float,
    ink: OrbInk,
    routePath: () -> List<List<Offset>>,
    reveal: MapReveal?,
) {
    val engine = cover.engine
    engine.compute(cover.orbTime(reduced))
    val orbCenter = Offset(size.width / 2, size.height / 2 - OrbLift.toPx())
    val k = OrbScale * density
    val p = cover.progress(reduced)
    when {
        cover.phase == CoverPhase.Loading -> {
            drawRect(ink.cover)
            drawOrb(engine, orbCenter, k, ink.dark)
        }
        reduced -> {
            drawRect(ink.cover.copy(alpha = 1 - p))
            drawOrb(engine, orbCenter, k, ink.dark, alpha = 1 - p)
        }
        else -> {
            drawRect(ink.cover.copy(alpha = 1 - smooth(p, 0.08f, 0.7f)))
            val runs = routePath().flatMap { visibleRuns(it, size.width, size.height) }
            drawScatter(engine, cover.plan, p, orbCenter, k, ink, RunPath(runs))
            // The map's stops pop in over the dots, never under them.
            reveal?.drawMarks?.invoke(this, p)
        }
    }
}

/** Where each dot is headed: its share along the line, a small start delay and which side it bows to. */
private class MeltPlan(n: Int) {
    val share = FloatArray(n)
    val delay = FloatArray(n)
    val side = FloatArray(n)

    /** Left-most dots go to the line's start: ranked by where they are as the melt begins. */
    fun prepare(engine: OrbEngine) {
        val n = engine.count
        val byX = (0 until n).sortedBy { engine.x[it] }
        byX.forEachIndexed { rank, i ->
            share[i] = LineInset + (1 - 2 * LineInset) * rank / max(1, n - 1)
            delay[i] = hash(i, 3.1) * 0.14f
            side[i] = if (hash(i, 9.2) < 0.5f) -1f else 1f
        }
    }
}

/** ML1 "scatter to stops": each dot arcs from the orb to its place along [line] (or outward without one). */
private fun DrawScope.drawScatter(
    engine: OrbEngine,
    plan: MeltPlan,
    p: Float,
    orbCenter: Offset,
    k: Float,
    orbInk: OrbInk,
    line: RunPath,
) {
    val dark = orbInk.dark
    val routeColor = orbInk.route
    val total = line.total
    // Into points each dot goes out as it lands (see [landing]); along lines they all fade together.
    val points = orbInk.points && total > 0f
    val fade = if (points) 1f else 1 - smooth(p, 0.78f, 1f)
    if (fade <= 0f) return
    val half = engine.size / 2
    val onLine = DotOnLine.toPx()
    val arcMax = ArcMax.toPx()
    for (o in 0 until engine.count) {
        val i = engine.order[o]
        val e = if (points) easeInOut(((p - plan.delay[i] * PointsStagger) / PointsSpan).coerceIn(0f, 1f))
        else easeInOut(((p - plan.delay[i]) / 0.58f).coerceIn(0f, 1f))
        val x0 = orbCenter.x + ((engine.x[i] - half) * k).toFloat()
        val y0 = orbCenter.y + ((engine.y[i] - half) * k).toFloat()
        val to = if (total > 0f) line.at(plan.share[i] * total)
        else Offset(orbCenter.x + (x0 - orbCenter.x) * 5f, orbCenter.y + (y0 - orbCenter.y) * 5f)
        val dx = to.x - x0
        val dy = to.y - y0
        val len = hypot(dx, dy).takeIf { it > 0f } ?: 1f
        val bow = sin(PI.toFloat() * e) * min(arcMax, len * 0.18f) * plan.side[i]
        val x = x0 + dx * e - dy / len * bow
        val y = y0 + dy * e + dx / len * bow
        val g = engine.grey(i, dark)
        val ink = lerp(Color(g, g, g), routeColor, if (total > 0f) smooth(e, 0.35f, 1f) else 0f)
        val land = if (points) landing(e) else 1f
        val a = (engine.a[i].toFloat() + (1 - engine.a[i].toFloat()) * e) * fade * land
        if (a < 0.02f) continue
        val r0 = (max(engine.rMin, engine.r[i]) * k).toFloat()
        drawCircle(ink.copy(alpha = a), radius = (r0 + (onLine - r0) * e) * land, center = Offset(x, y))
    }
}

/**
 * The route's screen points ([MapState] projection of [line], thinned to at most 200) in px of the
 * map's own box. Empty until the map exists.
 */
internal fun routeScreenPath(map: MapState?, line: List<LatLon>, density: Float): List<Offset> {
    if (map == null || line.size < 2) return emptyList()
    val step = max(1, line.size / 200)
    val picks = line.indices.step(step).toMutableList()
    if (picks.last() != line.lastIndex) picks += line.lastIndex
    // A map tearing down mid-melt can't project: the dots spray outward instead.
    return runCatching {
        picks.mapNotNull { idx ->
            map.screenLocationFromPosition(line[idx].position)?.let { Offset(it.x.value * density, it.y.value * density) }
        }
    }.getOrDefault(emptyList())
}

/** Every stretch of [points] inside a [w]×[h] box, cut exactly at its edges. */
internal fun visibleRuns(points: List<Offset>, w: Float, h: Float): List<List<Offset>> {
    val runs = ArrayList<List<Offset>>()
    var current: MutableList<Offset>? = null
    for (i in 1 until points.size) {
        val seg = clipSegment(points[i - 1], points[i], w, h)
        val run = current
        when {
            seg == null -> {
                if (run != null) runs += run
                current = null
            }
            run != null && run.last() == seg.first -> run += seg.second
            else -> {
                if (run != null) runs += run
                current = mutableListOf(seg.first, seg.second)
            }
        }
    }
    current?.let { runs += it }
    return runs.filter { r -> r.zipWithNext().any { (a, b) -> a != b } }
}

/** The part of segment [a]–[b] inside the box (Liang–Barsky), or null. */
private fun clipSegment(a: Offset, b: Offset, w: Float, h: Float): Pair<Offset, Offset>? {
    val d = b - a
    var t0 = 0f
    var t1 = 1f
    var outside = false
    val edges = floatArrayOf(-d.x, a.x, d.x, w - a.x, -d.y, a.y, d.y, h - a.y)
    for (e in 0 until 4) {
        val pe = edges[e * 2]
        val qe = edges[e * 2 + 1]
        when {
            pe == 0f -> if (qe < 0f) outside = true
            pe < 0f -> t0 = max(t0, qe / pe)
            else -> t1 = min(t1, qe / pe)
        }
    }
    return if (outside || t0 > t1) null else (if (t0 == 0f) a else a + d * t0) to (if (t1 == 1f) b else a + d * t1)
}

/** Several stretches of line walked as one: [at] finds the point [s] px along them all. */
private class RunPath(private val runs: List<List<Offset>>) {
    private val lengths = runs.map { r ->
        FloatArray(r.size).also { acc -> for (i in 1 until r.size) acc[i] = acc[i - 1] + (r[i] - r[i - 1]).getDistance() }
    }
    val total = lengths.sumOf { it.last().toDouble() }.toFloat()

    fun at(s: Float): Offset {
        var left = s
        for (k in runs.indices) {
            val len = lengths[k].last()
            if (left <= len || k == runs.lastIndex) return along(runs[k], lengths[k], left.coerceAtMost(len))
            left -= len
        }
        return Offset.Zero
    }

    private fun along(line: List<Offset>, acc: FloatArray, s: Float): Offset {
        var i = 1
        while (i < acc.size - 1 && acc[i] < s) i++
        val seg = (acc[i] - acc[i - 1]).takeIf { it > 0f } ?: 1f
        val u = ((s - acc[i - 1]) / seg).coerceIn(0f, 1f)
        return line[i - 1] + (line[i] - line[i - 1]) * u
    }
}

private fun easeInOut(u: Float): Float = if (u < 0.5f) 4 * u * u * u else 1 - (-2 * u + 2).pow(3) / 2

private fun hash(a: Int, b: Double): Float {
    val h = sin(a * 12.9898 + b * 78.233) * 43758.5453
    return (h - floor(h)).toFloat()
}
