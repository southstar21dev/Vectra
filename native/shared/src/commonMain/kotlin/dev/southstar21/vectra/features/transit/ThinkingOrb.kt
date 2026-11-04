package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.designsystem.components.LocalPageActive
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.util.roundHalfUpToInt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.truncate

/**
 * The dotted thought-orb loading indicator, drawn on a Canvas. Geometry, presets and ink
 * follow the original `thinking-orbs` engine: same lattice, same per-size preset tuning
 * (20 / 32 / 64), same shared clock (`t = seconds * presetSpeed`), same monochrome ink
 * (mirrored on dark substrates) and same reduced-motion behaviour (a single static frame at t = 0.6).
 */
enum class ThinkingOrbState(internal val label: String) {
    /** "rubik": latitude bands scramble, then click back solved. */
    Solving("Solving…"),

    /** "globe": a scan meridian sweeps a dotted globe. */
    Searching("Searching…"),
}

@Composable
fun ThinkingOrb(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    state: ThinkingOrbState = ThinkingOrbState.Solving,
) {
    val dark = Vectra.colors.isDark
    val reduced = Vectra.reduceMotion
    val sizeValue = size.value
    val engine = remember(state, sizeValue) { OrbEngine(state, sizeValue.toDouble()) }
    var t by remember { mutableDoubleStateOf(0.6) }

    val active = LocalPageActive.current
    LaunchedEffect(engine, reduced, active) {
        if (!active) return@LaunchedEffect
        if (reduced) {
            // Reduced motion: static representative frame (unscaled t = 0.6).
            t = 0.6
            return@LaunchedEffect
        }
        // All instances share one frame clock so they stay in phase.
        while (true) {
            withFrameNanos { nanos -> t = nanos / 1e9 * engine.speed }
        }
    }

    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = state.label },
    ) {
        engine.compute(t)
        val k = this.size.minDimension / sizeValue // dp-units → px
        val n = engine.count
        val order = engine.order
        for (o in 0 until n) {
            val i = order[o]
            val alpha = engine.a[i]
            if (alpha < 0.02) continue
            val w = engine.w[i].coerceIn(0.0, 1.0)
            val g = ((if (dark) 1 - w else w) * 255).roundToInt() / 255f
            drawCircle(
                color = Color(g, g, g, alpha.toFloat()),
                radius = (max(engine.rMin, engine.r[i]) * k).toFloat(),
                center = Offset((engine.x[i] * k).toFloat(), (engine.y[i] * k).toFloat()),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Engine (rubik + globe), allocation-free per frame.
// ---------------------------------------------------------------------------------------------

private fun hashD(a: Double, b: Double): Double {
    val h = sin(a * 12.9898 + b * 78.233) * 43758.5453
    return h - floor(h)
}

private fun angleDelta(a: Double, b: Double): Double = atan2(sin(a - b), cos(a - b))

/** Resolved preset: BASE_PROFILES[mode] scaled by PRESETS[mode][size] (scaleCounts/scaleRadii). */
private class OrbProfile(
    val speed: Double,
    val latRings: Int,
    val lonDensity: Int,
    val rBase: Double,
    val rDepth: Double,
    val rActive: Double,
    val rBoost: Double,
    val inkFar: Double,
    val inkSpan: Double,
    val rsPow: Double,
    val rMin: Double,
    val moveCount: Int,
    val scanMul: Double,
    val dimBase: Double,
)

private fun resolveProfile(state: ThinkingOrbState, size: Double): OrbProfile {
    // The package only ships 20 / 32 / 64 presets; pick the nearest one.
    val key = listOf(20, 32, 64).minBy { abs(it - size) }
    // speed, count, size (+ extra)
    return when (state) {
        ThinkingOrbState.Solving -> {
            val (speed, count, sz) = when (key) {
                20 -> Triple(1.95, 0.088, 1.9)
                32 -> Triple(1.8964, 0.1537, 1.4951)
                else -> Triple(1.82, 0.35, 1.05)
            }
            val rt = sqrt(count)
            OrbProfile(
                speed = speed,
                latRings = max(2, roundHalfUpToInt(15 * rt)),
                lonDensity = max(2, roundHalfUpToInt(40 * rt)),
                rBase = 0.6 * sz, rDepth = 1.7 * sz, rActive = 0.3 * sz, rBoost = 0.0,
                inkFar = 0.62, inkSpan = 0.54, rsPow = 0.6, rMin = 0.3,
                moveCount = 14, scanMul = 1.0, dimBase = 1.0,
            )
        }
        ThinkingOrbState.Searching -> {
            val (speed, count, sz) = when (key) {
                20 -> Triple(2.665, 0.105, 1.75)
                32 -> Triple(2.3803, 0.1839, 1.4769)
                else -> Triple(2.015, 0.42, 1.15)
            }
            val scanMul = when (key) { 20 -> 4.335; 32 -> 4.2301; else -> 4.08 }
            val rt = sqrt(count)
            OrbProfile(
                speed = speed,
                latRings = max(2, roundHalfUpToInt(17 * rt)),
                lonDensity = max(2, roundHalfUpToInt(44 * rt)),
                rBase = 0.6 * sz, rDepth = 1.7 * sz, rActive = 0.0, rBoost = 1.0,
                inkFar = 0.62, inkSpan = 0.54, rsPow = 0.6, rMin = 0.3,
                moveCount = 0, scanMul = scanMul, dimBase = 0.45,
            )
        }
    }
}

private class OrbEngine(private val state: ThinkingOrbState, private val size: Double) {
    private val p = resolveProfile(state, size)
    val speed: Double get() = p.speed
    val rMin: Double get() = p.rMin

    // Base lat/lon lattice (unit sphere), plus each dot's longitude for the globe scan.
    private val bx: DoubleArray
    private val by: DoubleArray
    private val bz: DoubleArray
    private val blon: DoubleArray
    val count: Int

    init {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val zs = ArrayList<Double>()
        val lons = ArrayList<Double>()
        for (li in 0..p.latRings) {
            val lat = -PI / 2 + li.toDouble() / p.latRings * PI
            val cosLat = cos(lat)
            val sinLat = sin(lat)
            val lonCount = max(1, roundHalfUpToInt(abs(cosLat) * p.lonDensity))
            for (lj in 0 until lonCount) {
                val lon = lj.toDouble() / lonCount * 2 * PI
                xs += cosLat * cos(lon); ys += sinLat; zs += cosLat * sin(lon); lons += lon
            }
        }
        count = xs.size
        bx = xs.toDoubleArray(); by = ys.toDoubleArray(); bz = zs.toDoubleArray()
        blon = lons.toDoubleArray()
    }

    // Rubik moves (makeMoves): axis, slab [lo, lo + 0.5), ±90°.
    private val mvAxis = IntArray(p.moveCount) { i -> min(2, floor(hashD(i.toDouble(), 2.3) * 3).toInt()) }
    private val mvLo = DoubleArray(p.moveCount) { i -> -1 + 0.5 * min(3, floor(hashD(i.toDouble(), 5.9) * 4).toInt()) }
    private val mvAng = DoubleArray(p.moveCount) { i -> (if (hashD(i.toDouble(), 7.7) < 0.5) 1 else -1) * PI / 2 }
    private val amount = DoubleArray(p.moveCount)
    private var active = -1

    // Per-frame output, reused.
    val x = DoubleArray(count)
    val y = DoubleArray(count)
    private val z = DoubleArray(count)
    val r = DoubleArray(count)
    val w = DoubleArray(count)
    val a = DoubleArray(count) { 1.0 }
    val order = IntArray(count)

    private var lastT = Double.NaN
    private val rs = (size / 300).pow(p.rsPow)

    fun compute(t: Double) {
        if (t == lastT) return
        lastT = t
        when (state) {
            ThinkingOrbState.Solving -> rubik(t)
            ThinkingOrbState.Searching -> globe(t)
        }
        // Stable z-sort far → near (ties keep lattice order): insertion sort from identity.
        for (i in 0 until count) order[i] = i
        for (i in 1 until count) {
            val v = order[i]
            val zv = z[v]
            var j = i - 1
            while (j >= 0 && z[order[j]] > zv) { order[j + 1] = order[j]; j-- }
            order[j + 1] = v
        }
    }

    // Projection (makeProj) parameters, set per frame.
    private var pSt = 0.0; private var pCt = 1.0; private var pSy = 0.0; private var pCy = 1.0
    private var pCx = 0.0; private var pCyy = 0.0; private var pScale = 1.0

    private fun setProj(yaw: Double, tilt: Double, cx: Double, cy: Double, scale: Double) {
        pSt = sin(tilt); pCt = cos(tilt); pSy = sin(yaw); pCy = cos(yaw)
        pCx = cx; pCyy = cy; pScale = scale
    }

    private fun project(i: Int, px: Double, py: Double, pz: Double) {
        val x1 = px * pCy + pz * pSy
        val z1 = -px * pSy + pz * pCy
        val y1 = py * pCt - z1 * pSt
        val z2 = py * pSt + z1 * pCt
        x[i] = pCx + x1 * pScale
        y[i] = pCyy - y1 * pScale
        z[i] = z2
    }

    /** solveCycle(time, count, slotDur = 0.42, rest = 1.2). */
    private fun solveCycle(time: Double) {
        val n = p.moveCount
        val slotDur = 0.42
        val cyc = 2 * n * slotDur + 1.2
        val tc = truncatedRem(time, cyc)
        amount.fill(0.0)
        active = -1
        if (tc < 2 * n * slotDur) {
            val slot = floor(tc / slotDur).toInt()
            val pr = (tc - slot * slotDur) / slotDur
            val cl = min(1.0, pr / 0.7)
            val ep = 1 - (1 - cl).pow(3)
            if (slot < n) {
                for (i in 0 until slot) amount[i] = 1.0
                amount[slot] = ep
                active = slot
            } else {
                val u = 2 * n - 1 - slot
                for (i in 0 until u) amount[i] = 1.0
                amount[u] = 1 - ep
                active = u
            }
        }
    }

    private fun rubik(t: Double) {
        val c = size / 2
        setProj(t * 0.55, 0.35 + 0.1 * sin(t * 0.9), c, c, size / 2 * 0.82)
        solveCycle(t)
        for (i in 0 until count) {
            var px = bx[i]; var py = by[i]; var pz = bz[i]
            var inActive = false
            for (m in 0 until p.moveCount) {
                val am = amount[m]
                if (am <= 0) continue
                val axis = mvAxis[m]
                val coord = when (axis) { 0 -> px; 1 -> py; else -> pz }
                if (coord < mvLo[m] || coord >= mvLo[m] + 0.5) continue
                if (m == active) inActive = true
                val ang = mvAng[m] * am
                val ca = cos(ang); val sa = sin(ang)
                when (axis) {
                    0 -> { val y2 = py * ca - pz * sa; pz = py * sa + pz * ca; py = y2 }
                    1 -> { val x2 = px * ca + pz * sa; pz = -px * sa + pz * ca; px = x2 }
                    else -> { val x2 = px * ca - py * sa; py = px * sa + py * ca; px = x2 }
                }
            }
            project(i, px, py, pz)
            val depth = (z[i] + 1) / 2
            r[i] = (p.rBase + p.rDepth * depth + (if (inActive) p.rActive else 0.0)) * rs
            w[i] = p.inkFar - p.inkSpan * depth - (if (inActive) 0.14 else 0.0)
            a[i] = 1.0
        }
    }

    private fun globe(t: Double) {
        val spin = 0.5
        val c = size / 2
        setProj(t * spin, 0.4 + 0.06 * sin(t * 0.35), c, c, size / 2 * 0.82)
        val scan = t * (spin + (1.7 - spin) * p.scanMul)
        for (i in 0 until count) {
            project(i, bx[i], by[i], bz[i])
            val zi = z[i]
            val depth = (zi + 1) / 2
            val d = angleDelta(blon[i] + t * spin, scan)
            val boost = exp(-(d * d) / 0.18) * max(0.0, zi)
            r[i] = (p.rBase + p.rDepth * depth + p.rBoost * boost) * rs
            w[i] = p.inkFar - p.inkSpan * depth
            a[i] = p.dimBase + (1 - p.dimBase) * min(1.0, boost)
        }
    }
}

/** Truncating remainder (sign follows the dividend). */
private fun truncatedRem(a: Double, b: Double): Double = a - b * truncate(a / b)
