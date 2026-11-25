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
import androidx.compose.ui.graphics.drawscope.DrawScope
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
 * The dotted globe loading orb ("Searching"), drawn on a Canvas by [drawOrb]. Geometry, presets
 * and ink follow the original `thinking-orbs` engine: same lattice, same per-size preset tuning
 * (20 / 32 / 64) and same monochrome ink (mirrored on dark substrates).
 */

/** Grey of dot [i]'s ink (0..1): monochrome, mirrored on dark substrates. */
internal fun OrbEngine.grey(i: Int, dark: Boolean): Float {
    val w = w[i].coerceIn(0.0, 1.0)
    return ((if (dark) 1 - w else w) * 255).roundToInt() / 255f
}

/** Draws the computed orb centred on [center], [k] px per orb dp. */
internal fun DrawScope.drawOrb(engine: OrbEngine, center: Offset, k: Float, dark: Boolean, alpha: Float = 1f) {
    val half = engine.size / 2
    for (o in 0 until engine.count) {
        val i = engine.order[o]
        val a = engine.a[i] * alpha
        if (a < 0.02) continue
        val g = engine.grey(i, dark)
        drawCircle(
            color = Color(g, g, g, a.toFloat()),
            radius = (max(engine.rMin, engine.r[i]) * k).toFloat(),
            center = Offset(center.x + ((engine.x[i] - half) * k).toFloat(), center.y + ((engine.y[i] - half) * k).toFloat()),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Engine (globe), allocation-free per frame.
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
    val rBoost: Double,
    val inkFar: Double,
    val inkSpan: Double,
    val rsPow: Double,
    val rMin: Double,
    val scanMul: Double,
    val dimBase: Double,
)

private fun resolveProfile(size: Double): OrbProfile {
    // The package only ships 20 / 32 / 64 presets; pick the nearest one.
    val key = listOf(20, 32, 64).minBy { abs(it - size) }
    val (speed, count, sz) = when (key) {
        20 -> Triple(2.665, 0.105, 1.75)
        32 -> Triple(2.3803, 0.1839, 1.4769)
        else -> Triple(2.015, 0.42, 1.15)
    }
    val scanMul = when (key) { 20 -> 4.335; 32 -> 4.2301; else -> 4.08 }
    val rt = sqrt(count)
    return OrbProfile(
        speed = speed,
        latRings = max(2, roundHalfUpToInt(17 * rt)),
        lonDensity = max(2, roundHalfUpToInt(44 * rt)),
        rBase = 0.6 * sz, rDepth = 1.7 * sz, rBoost = 1.0,
        inkFar = 0.62, inkSpan = 0.54, rsPow = 0.6, rMin = 0.3,
        scanMul = scanMul, dimBase = 0.45,
    )
}

/** The orb's dots for a frame: positions in the orb's own [size] box (dp), radius, ink and alpha, drawn in [order]. */
internal class OrbEngine(val size: Double) {
    private val p = resolveProfile(size)
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
        globe(t)
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
