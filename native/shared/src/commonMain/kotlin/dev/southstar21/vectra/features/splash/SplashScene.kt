package dev.southstar21.vectra.features.splash

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A faint dotted orb over a dotted Budapest skyline (Gellért Hill + Citadella + Liberty Statue,
 * the Rock Chapel, Liberty Bridge with a tram, Corvinus E building) reflected in the Danube.
 * Shapes live in fixed scene units (260 wide, waterline at B), are rasterised once and sampled
 * into dots, sparse and sized by depth; the bridge lamps and the chapel's cross glow in the
 * accent. Frames only draw dots, batched. The exit disperses every dot outwards ([DisperseExitMs]).
 */
const val SceneW = 260f
const val B = 424f
const val DisperseExitMs = 700f
private const val TAU = (PI * 2).toFloat()

internal fun ease(x: Float) = 1f - (1f - x.coerceIn(0f, 1f)).pow(3)
internal fun easeIn(x: Float) = x.coerceIn(0f, 1f).pow(2)
internal fun smoothstep(a: Float, b: Float, x: Float): Float {
    val u = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return u * u * (3 - 2 * u)
}

/** How far a dot (its [seed] 0..1) has drifted out (scene units) at disperse progress [u]. */
internal fun disperseDrift(u: Float, seed: Float, far: Float, base: Float, near: Float) = easeIn(u) * (base + far * seed) + ease(u) * near

/** A dot's remaining opacity at disperse progress [u]: each fades on its own beat. */
internal fun disperseFade(u: Float, seed: Float) = 1f - smoothstep(0.1f + 0.3f * seed, 0.75f + 0.2f * seed, u)

/** Spacing between sampled dots: sparser than the first scene, so lighter and crisper. */
private const val SPACING = 1.4f

private const val BL = 58f
private const val BR = 202f
private const val P1 = 98f
private const val P2 = 162f
private const val DECK = B - 14f
private const val SX = 86f
private const val SG = B - 106f
private const val SK = 0.5f

/** Fill + stroke parts of one silhouette layer. */
private class Shape(val fill: Path = Path(), val strokes: MutableList<Pair<Path, Float>> = mutableListOf())

private fun Path.rect(x: Float, y: Float, w: Float, h: Float) = addRect(Rect(x, y, x + w, y + h))
private fun Path.circle(cx: Float, cy: Float, r: Float) = addOval(Rect(cx - r, cy - r, cx + r, cy + r))
private fun Path.poly(vararg pts: Float) {
    moveTo(pts[0], pts[1])
    var i = 2
    while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
    close()
}

private fun farShape(s: Shape) = s.fill.apply {
    moveTo(-2f, B); lineTo(-2f, B - 52); cubicTo(26f, B - 62, 46f, B - 96, 74f, B - 100)
    lineTo(98f, B - 100); cubicTo(120f, B - 100, 132f, B - 72, 150f, B - 48)
    cubicTo(164f, B - 32, 180f, B - 18, 198f, B - 8); lineTo(198f, B); close()
    poly(68f, B - 100, 70f, B - 106, 102f, B - 106, 104f, B - 100)
    var x = 71f
    while (x < 101f) { rect(x, B - 108, 1.6f, 2.2f); x += 3.2f }
    rect(66f, B - 109, 5f, 9f); rect(101f, B - 109, 5f, 9f)
}

private fun quad(x: Float, x0: Float, y0: Float, cy: Float, x1: Float, y1: Float): Float {
    val t = (x - x0) / (x1 - x0)
    return (1 - t) * (1 - t) * y0 + 2 * (1 - t) * t * cy + t * t * y1
}

private fun chord(x: Float) = when {
    x < P1 -> quad(x, BL, DECK - 4, DECK - 8, P1 - 1, B - 38)
    x < P2 -> quad(x, P1 - 1, B - 38, B - 10, P2 + 1, B - 38)
    else -> quad(x, P2 + 1, B - 38, DECK - 8, BR, DECK - 4)
}

private fun nearShape(s: Shape) {
    s.fill.apply {
        rect(-2f, B - 7, BL + 2, 7f); rect(BR, B - 7, 62f, 7f)
        rect(-2f, DECK, BL + 2, B - 7 - DECK); rect(-2f, DECK - 2, BL + 2, 2.5f)
        rect(BR, B - 35, 60f, 28f)
        rect(BR, B - 39, 10f, 4f); rect(BR + 50, B - 39, 10f, 4f)
        rect(BR + 20, B - 43, 20f, 8f)
        poly(BR + 20, B - 43, BR + 30, B - 47, BR + 40, B - 43)
        for (x in listOf(BR + 21, BR + 25, BR + 35, BR + 39)) { rect(x - 0.6f, B - 46.5f, 1.2f, 3.5f); circle(x, B - 47.3f, 0.9f) }
        for ((x, d) in listOf(BL to 1f, BR to -1f)) {
            rect(if (d > 0) x else x - 8, DECK, 8f, B - DECK)
            val px = x + d * 3
            rect(px - 1.5f, B - 30, 3f, 16f); circle(px, B - 32, 1.8f)
        }
        rect(BL, DECK - 2, BR - BL, 2.5f)
        for (px in listOf(P1, P2)) {
            rect(px - 3.5f, DECK, 7f, B - DECK)
            poly(px - 3.5f, DECK - 2, px - 1.5f, B - 50, px + 1.5f, B - 50, px + 3.5f, DECK - 2)
            rect(px - 0.6f, B - 55, 1.2f, 5f)
            circle(px, B - 57, 2.3f)
            poly(px - 6, B - 63, px, B - 59.5f, px + 6, B - 63, px, B - 60.6f)
        }
    }
    s.strokes += Path().apply {
        moveTo(BL, DECK - 4); quadraticTo(BL + 22, DECK - 8, P1 - 1, B - 38)
        quadraticTo(130f, B - 10, P2 + 1, B - 38); quadraticTo(BR - 22, DECK - 8, BR, DECK - 4)
    } to 1.4f
    s.strokes += Path().apply {
        var x = BL + 6
        while (x <= BR - 6) {
            if (abs(x - P1) >= 5 && abs(x - P2) >= 5) { moveTo(x, DECK - 2); lineTo(x, chord(x)) }
            x += 6
        }
    } to 0.9f
}

private fun chapelShape(s: Shape) = s.fill.apply {
    poly(-2f, B - 16, -2f, B - 42, 3f, B - 43, 7f, B - 45, 11f, B - 44, 15f, B - 50, 20f, B - 51, 26f, B - 50, 29f, B - 46, 33f, B - 47, 37f, B - 40, 40f, B - 35, 41f, B - 16)
    rect(19.4f, B - 53, 6.2f, 2f); for (x in listOf(19.6f, 22.2f, 24.8f)) rect(x, B - 55, 0.8f, 2f)
    rect(21.8f, B - 67, 1.6f, 14f); rect(18.6f, B - 63.4f, 8f, 1.6f)
    rect(40f, B - 29, 14f, 13f)
    poly(39.5f, B - 29, 42.5f, B - 42, 45.5f, B - 29)
    rect(48f, B - 36, 7f, 20f); poly(47.4f, B - 36, 51.5f, B - 43, 55.6f, B - 36)
    for (x in listOf(5f, 25f, 35f)) { rect(x, B - 26, 0.9f, 10f); rect(x - 1, B - 26.6f, 2.9f, 1f) }
}

/** Liberty Statue, drawn at half scale around its base. */
private fun statueShape(s: Shape) {
    val p = Path().apply {
        rect(SX - 6, SG - 3, 12f, 5f)
        poly(SX - 4, SG - 3, SX - 3, SG - 30, SX + 3, SG - 30, SX + 4, SG - 3)
        rect(SX - 3.8f, SG - 32, 7.6f, 2f)
        val f = SG - 32
        poly(SX - 2.9f, f, SX - 2.3f, f - 13, SX - 3.3f, f - 19.5f, SX + 3.3f, f - 19.5f, SX + 2.3f, f - 13, SX + 2.9f, f)
        poly(SX - 3.3f, f - 19.5f, SX - 4.6f, f - 14.5f, SX - 3.4f, f - 15, SX - 2.4f, f - 18)
        poly(SX + 3.3f, f - 19.5f, SX + 4.6f, f - 14.5f, SX + 3.4f, f - 15, SX + 2.4f, f - 18)
        circle(SX, f - 21.4f, 1.6f)
        poly(SX - 3.3f, f - 19, SX - 4.9f, f - 30, SX - 3.5f, f - 30.2f, SX - 2.1f, f - 19.4f)
        poly(SX + 3.3f, f - 19, SX + 4.9f, f - 30, SX + 3.5f, f - 30.2f, SX + 2.1f, f - 19.4f)
        moveTo(SX - 13, f - 31); quadraticTo(SX - 2, f - 36.5f, SX + 10, f - 33.2f); lineTo(SX + 14, f - 33.4f)
        lineTo(SX + 14, f - 32.6f); lineTo(SX + 10, f - 31.6f); quadraticTo(SX - 2, f - 29.4f, SX - 13, f - 31); close()
        var x = SX - 12
        while (x < SX + 9) {
            val t = (x - (SX - 13)) / 23f
            val y = f - 31 - 4.6f * t * (1 - t) * 2.2f
            poly(x, y, x - 2.4f, y - 2.2f, x + 0.9f, y + 0.2f)
            x += 1.7f
        }
    }
    val m = Matrix().apply { translate(SX, SG); scale(SK, SK); translate(-SX, -SG) }
    p.transform(m)
    s.fill.addPath(p)
}

private fun arch(x: Float, y: Float, cx: Float, top: Float, r: Float, bottom: Float) =
    abs(x - cx) < r && y < bottom && (y > top + r || hypot(x - cx, y - (top + r)) < r)

private fun isOpening(x: Float, y: Float) =
    arch(x, y, 12f, B - 36, 3.6f, B - 16) || arch(x, y, 31f, B - 31, 2.6f, B - 16) || (x > 49.5f && x < 53.5f && y > B - 33 && y < B - 30)

private fun isWindow(x: Float, y: Float) =
    isOpening(x, y) || (x > 205 && x < 259 && listOf(B - 32, B - 25, B - 18).any { y > it && y < it + 3.2f } && (x - 205) % 5.4f < 2.6f)

/** Rasterise a layer over a scene-unit box at `scale` px per unit; returns an alpha lookup. */
private fun rasterise(build: (Shape) -> Unit, x0: Float, y0: Float, w: Float, h: Float, scale: Float): (Float, Float) -> Int {
    val cw = (w * scale).toInt() + 1
    val ch = (h * scale).toInt() + 1
    val bmp = ImageBitmap(cw, ch)
    val canvas = Canvas(bmp)
    canvas.scale(scale, scale)
    canvas.translate(-x0, -y0)
    val shape = Shape().also(build)
    canvas.drawPath(shape.fill, Paint().apply { color = Color.Black })
    for ((path, width) in shape.strokes) canvas.drawPath(path, Paint().apply { color = Color.Black; style = PaintingStyle.Stroke; strokeWidth = width })
    val pixels = IntArray(cw * ch)
    bmp.readPixels(pixels)
    return { x, y ->
        val xi = ((x - x0) * scale).roundToInt()
        val yi = ((y - y0) * scale).roundToInt()
        if (xi < 0 || yi < 0 || xi >= cw || yi >= ch) 0 else (pixels[yi * cw + xi] ushr 24) and 0xFF
    }
}

/** x, y, brightness, phase, radius, whether it is a lamp head (drawn in the accent). */
private class SkyDot(val x: Float, val y: Float, val e: Float, val r: Float, val rad: Float, val lamp: Boolean = false)

/** The lamp heads: the pylons' globes, the abutments' lamps, the chapel's cross. */
private val Lamps = listOf(P1 to B - 57, P2 to B - 57, BL + 3 to B - 32, BR - 3 to B - 32, 22.6f to B - 62.6f)

private fun lampAt(x: Float, y: Float) = Lamps.any { (lx, ly) -> hypot(x - lx, y - ly) < 3.2f }

private const val TRAM_W = 30f
private const val TRAM_H = 8f
private const val TRAM_Y = B - 16 - TRAM_H
private const val TRAM_LOOP_MS = 7000f
private const val ORB_R = 230f
private const val OrbDots = 620
private const val ORB_CY = B - 81
private const val LIGHTS_START_MS = 100f
private val TramColor = Color(255, 205, 0)

/** A frame with the city fully lit and the tram mid-bridge, for reduced motion. */
const val STILL_FRAME_ELAPSED = LIGHTS_START_MS + 500 + TRAM_LOOP_MS / 2

class SplashScene(seed: Int = 7) {
    private val rnd = Random(seed)
    private val skyDots: List<SkyDot>
    private val tramDots: List<Offset>
    private val orbPts: List<FloatArray>

    init {
        val far = rasterise(::farShape, -2f, 300f, SceneW + 4, B - 298, 1f)
        val near = rasterise({ nearShape(it); chapelShape(it) }, -2f, 330f, SceneW + 4, B - 328, 1f)
        val chapel = rasterise(::chapelShape, -2f, B - 72, 62f, 74f, 4f)
        val statue = rasterise(::statueShape, SX - 10, SG - 40, 20f, 44f, 4f)
        val out = ArrayList<SkyDot>(5000)
        fun nearby(x: Float, y: Float) = near(x, y) > 40 || near(x - 2, y) > 40 || near(x + 2, y) > 40 || near(x, y - 2) > 40 || near(x, y + 2) > 40
        var y = 340f
        while (y < B + 2) {
            var x = 0f
            while (x < SceneW) {
                if (near(x, y) >= 80 && chapel(x, y) == 0 && !isWindow(x, y)) {
                    val edge = near(x, y - 3) < 80 || near(x - 3, y) < 80 || near(x + 3, y) < 80
                    out += SkyDot(x, y, if (edge) 1f else 0.45f, rnd.nextFloat(), if (edge) 0.95f else 0.7f, lampAt(x, y))
                }
                x += 2.1f * SPACING
            }
            y += 2.1f * SPACING
        }
        y = 300f
        while (y < B + 2) {
            var x = 0f
            while (x < SceneW) {
                if (far(x, y) >= 80 && !nearby(x, y)) {
                    val edge = far(x, y - 3) < 80 || far(x - 3, y) < 80 || far(x + 3, y) < 80
                    // Further back, smaller.
                    out += SkyDot(x, y, if (edge) 0.6f else 0.18f, rnd.nextFloat(), if (edge) 0.6f else 0.45f)
                }
                x += 2.4f * SPACING
            }
            y += 2.4f * SPACING
        }
        y = B - 70
        while (y < B) {
            var x = 0.4f
            while (x < 58f) {
                if (chapel(x, y) >= 100 && !isOpening(x, y)) {
                    val edge = chapel(x, y - 1.6f) < 100 || chapel(x - 1.6f, y) < 100 || chapel(x + 1.6f, y) < 100 ||
                        isOpening(x, y + 1.6f) || isOpening(x - 1.6f, y) || isOpening(x + 1.6f, y)
                    out += SkyDot(x, y, if (edge) 1.1f else 0.4f, rnd.nextFloat(), 0.5f, lampAt(x, y))
                }
                x += 1.15f * min(SPACING, 1.25f)
            }
            y += 1.15f * min(SPACING, 1.25f)
        }
        y = SG - 38
        while (y < SG + 2) {
            var x = SX - 9
            while (x < SX + 9) {
                if (statue(x, y) > 100) out += SkyDot(x, y, if (y > SG - 32 * SK) 0.8f else 1.5f, rnd.nextFloat(), 0.42f)
                x += 0.55f * min(SPACING, 1.2f)
            }
            y += 0.55f * min(SPACING, 1.2f)
        }
        skyDots = out
        tramDots = sampleTram()
        orbPts = List(OrbDots) { i ->
            val yy = 1f - (i / (OrbDots - 1f)) * 2f
            val r = sqrt(1f - yy * yy)
            val t = i * 2.39996f
            floatArrayOf(cos(t) * r, yy, sin(t) * r)
        }
    }

    private fun sampleTram(): List<Offset> {
        val w = (TRAM_W + 4).toInt()
        val h = (TRAM_H + 8).toInt()
        val bmp = ImageBitmap(w, h)
        val c = Canvas(bmp)
        val black = Paint().apply { color = Color.Black }
        c.drawPath(Path().apply {
            addRoundRect(RoundRect(Rect(1f, 5f, 1f + TRAM_W, 5f + TRAM_H), CornerRadius(3f), CornerRadius(3f), CornerRadius(1f), CornerRadius(1f)))
        }, black)
        val clear = Paint().apply { blendMode = BlendMode.Clear }
        var x = 5f
        while (x < TRAM_W - 3) { c.drawRect(Rect(x, 7f, x + 3.4f, 9.6f), clear); x += 5 }
        val stroke = Paint().apply { color = Color.Black; style = PaintingStyle.Stroke; strokeWidth = 0.8f }
        c.drawPath(Path().apply {
            moveTo(TRAM_W / 2 - 3, 5f); lineTo(TRAM_W / 2, 1.5f); lineTo(TRAM_W / 2 + 3, 5f)
            moveTo(TRAM_W / 2 - 4, 1f); lineTo(TRAM_W / 2 + 4, 1f)
        }, stroke)
        val px = IntArray(w * h)
        bmp.readPixels(px)
        val out = mutableListOf<Offset>()
        var yy = 0f
        while (yy < h) {
            var xx = 0f
            while (xx < w) {
                val xi = xx.roundToInt().coerceAtMost(w - 1)
                val yi = yy.roundToInt().coerceAtMost(h - 1)
                if (((px[yi * w + xi] ushr 24) and 0xFF) > 90) out += Offset(xx, yy)
                xx += 1.6f
            }
            yy += 1.5f
        }
        return out
    }

    /** Dots grouped by (alpha bucket, radius) so a frame is a handful of drawPoints calls. */
    private class Batch {
        val buckets = HashMap<Int, MutableList<Offset>>()
        fun add(x: Float, y: Float, r: Float, a: Float) {
            if (a <= 0.004f) return
            val ai = min(31, (a * 32).roundToInt())
            val ri = (r * 10).roundToInt().coerceIn(1, 40)
            buckets.getOrPut(ai * 64 + ri) { ArrayList() }.add(Offset(x, y))
        }
        fun flush(d: DrawScope, color: Color, gain: Float) {
            for ((key, pts) in buckets) {
                val ai = key / 64
                val r = (key % 64) / 10f
                d.drawPoints(pts, PointMode.Points, color.copy(alpha = min(1f, ai / 32f * gain)), strokeWidth = r * 2, cap = StrokeCap.Round)
            }
            buckets.clear()
        }
    }

    /** Solid silhouette of the whole skyline, used to hide the orb behind the city. */
    private val solid = Shape().also { farShape(it); nearShape(it); chapelShape(it); statueShape(it) }

    private val fg = Batch()
    private val lamp = Batch()
    private val tram = Batch()

    // The disperse in progress (0 while the splash stands): every dot drifts out from (cx, cy).
    private var u = 0f
    private var cx = 0f
    private var cy = 0f

    /** Adds a dot, moved, shrunk and faded by the disperse. [seed] staggers it. */
    private fun put(batch: Batch, x: Float, y: Float, r: Float, a: Float, seed: Float) {
        if (u <= 0f) {
            batch.add(x, y, r, a)
            return
        }
        val dx = x - cx
        val dy = y - cy
        val len = hypot(dx, dy).takeIf { it > 0f } ?: 1f
        val k = disperseDrift(u, seed, far = 120f, base = 50f, near = 14f)
        batch.add(x + dx / len * k, y + dy / len * k, r * (1 - 0.55f * u), a * disperseFade(u, seed))
    }

    /**
     * Draws one frame. The DrawScope must already map scene units to the screen. [exit] is the
     * disperse's progress (null while the splash stands); [centerY] where it spreads from (scene units).
     */
    fun draw(d: DrawScope, elapsed: Float, exit: Float?, time: Float, view: Rect, colors: SplashColors, gain: Float, centerY: Float) {
        u = exit ?: 0f
        cx = SceneW / 2
        cy = centerY
        drawOrb(d, elapsed, time, view, colors.fg, colors.bg, gain)
        drawSkyline(d, elapsed, time, view, colors, gain)
    }

    private fun rotY(p: FloatArray, a: Float) = floatArrayOf(p[0] * cos(a) + p[2] * sin(a), p[1], -p[0] * sin(a) + p[2] * cos(a))
    private fun rotX(p: FloatArray, a: Float) = floatArrayOf(p[0], p[1] * cos(a) - p[2] * sin(a), p[1] * sin(a) + p[2] * cos(a))

    private fun drawOrb(d: DrawScope, elapsed: Float, time: Float, view: Rect, fgColor: Color, bg: Color, gain: Float) {
        val fade = ease(elapsed / 900f)
        val ox = SceneW / 2
        val oy = ORB_CY
        if (fade > 0) {
            orbPts.forEachIndexed { i, pt ->
                val q = rotX(rotY(pt, time * 0.00009f), -0.5f)
                val depth = (q[2] + 1) / 2
                put(fg, ox + q[0] * ORB_R, oy + q[1] * ORB_R, 0.6f + depth * 1.1f, fade * (0.03f + depth * 0.2f), seedOf(i, 5.1f))
            }
            for (i in 0 until 70) {
                val a = time * 0.00035f + i * (TAU / 70)
                val trail = if (i % 35 < 10) 1f - (i % 35) / 10f else 0.1f
                val q = rotY(rotX(floatArrayOf(cos(a) * 1.02f, 0f, sin(a) * 1.02f), 0.42f), 0.3f)
                val depth = (q[2] + 1) / 2
                put(fg, ox + q[0] * ORB_R, oy + q[1] * ORB_R, (0.7f + depth * 1.4f) * (0.6f + trail * 0.6f), fade * (0.05f + trail * 0.45f) * (0.3f + depth * 0.7f), seedOf(i, 9.7f))
            }
            fg.flush(d, fgColor, gain)
        }
        // The vignette lifts as the dots disperse.
        val vignette = 1f - smoothstep(0f, 0.4f, u)
        d.drawRect(
            Brush.radialGradient(
                (104f / 390f) to bg.copy(alpha = 0f),
                1f to bg.copy(alpha = 0.85f * vignette),
                center = Offset(ox, B - 190),
                radius = 390f,
            ),
            topLeft = view.topLeft,
            size = view.size,
        )
    }

    private fun drawSkyline(d: DrawScope, elapsed: Float, time: Float, view: Rect, colors: SplashColors, gain: Float) {
        val bg = colors.bg
        // Solid silhouette hides the orb behind the city; the river below is opaque. Both melt
        // away early in the disperse, so the city's dots drift over whatever is behind.
        val solidFade = 1f - smoothstep(0f, 0.3f, u)
        d.drawPath(solid.fill, bg, alpha = solidFade)
        for ((p, w) in solid.strokes) d.drawPath(p, bg, alpha = solidFade, style = Stroke(w))
        d.drawRect(
            Brush.verticalGradient(0f to bg.copy(alpha = 0.95f), 1f to bg, startY = B, endY = view.bottom),
            topLeft = Offset(view.left, B + 1),
            size = Size(view.width, max(0f, view.bottom - B)),
            alpha = solidFade,
        )
        val wide = view.left < -4
        fun edge(x: Float) = if (wide) min(1f, min(max(0f, (x + 2) / 30f), max(0f, (SceneW + 2 - x) / 30f))) else 1f
        fun lights(x: Float, r: Float) = ease((elapsed - LIGHTS_START_MS - (x / SceneW) * 600 - r * 80) / 350f)
        for (dot in skyDots) {
            val on = lights(dot.x, dot.r) * edge(dot.x)
            if (on <= 0) continue
            if (dot.lamp) {
                put(lamp, dot.x, dot.y, dot.rad * 1.15f, on, dot.r)
            } else {
                val twinkle = if (dot.e < 1 && dot.r > 0.93f) 0.35f * (0.5f + 0.5f * sin(time * 0.003f + dot.r * 40)) else 0f
                put(fg, dot.x, dot.y, dot.rad, on * (0.08f + dot.e * 0.32f + twinkle), dot.r)
            }
            val ry = B + (B - dot.y) * 0.55f + 3
            put(fg, dot.x + sin(time * 0.002f + dot.y * 0.35f) * 1.4f, ry, 0.6f, on * (0.03f + dot.e * 0.07f) * (1 - (ry - B) / 60), dot.r)
        }
        var x = 1f
        while (x < SceneW) { put(fg, x, B + 2, 0.5f, lights(x, 0f) * edge(x) * 0.12f, seedOf(x.toInt(), 2.2f)); x += 3.2f }
        fg.flush(d, colors.fg, gain)
        lamp.flush(d, colors.lamp, 1f)

        val tramT = elapsed - LIGHTS_START_MS - 500
        if (tramT > 0) {
            val k = (tramT % TRAM_LOOP_MS) / TRAM_LOOP_MS
            val x0 = BL - TRAM_W + k * (BR - BL + TRAM_W)
            val arrive = ease(tramT / 400f)
            tramDots.forEachIndexed { i, p ->
                val tx = x0 + p.x
                val a = arrive * min(1f, min(max(0f, (tx - BL - 2) / 8f), max(0f, (BR - 2 - tx) / 8f)))
                if (a > 0) {
                    val ty = TRAM_Y - 5 + p.y
                    put(tram, tx, ty, 0.7f, a * 0.9f, seedOf(i, 3.3f))
                    val ry = B + (B - ty) * 0.55f + 3
                    put(tram, tx + sin(time * 0.002f + ty * 0.35f) * 1.4f, ry, 0.6f, a * 0.16f * (1 - (ry - B) / 60), seedOf(i, 6.6f))
                }
            }
            tram.flush(d, TramColor, 1f)
        }
    }
}

/** The scene's inks: the dots, the lamps (the accent, lifted a little), and the backdrop they melt into. */
class SplashColors(val fg: Color, val lamp: Color, val bg: Color)

/** A steady pseudo-random 0..1 for [i]: staggers dots that have no phase of their own. */
private fun seedOf(i: Int, salt: Float): Float {
    val h = sin(i * 12.9898f + salt * 78.233f) * 43758.547f
    return h - kotlin.math.floor(h)
}

/** Maps the scene onto a screen: skyline spans the width, waterline at 81.5% height. */
data class SceneMapping(val scale: Float, val offsetX: Float, val offsetY: Float, val view: Rect)

fun sceneTransform(screenW: Float, screenH: Float, density: Float): SceneMapping {
    val scale = min(screenW / SceneW, 2.4f * density)
    val offsetX = screenW / 2 - (SceneW / 2) * scale
    val offsetY = screenH * 0.815f - B * scale
    return SceneMapping(scale, offsetX, offsetY, Rect(-offsetX / scale, -offsetY / scale, (screenW - offsetX) / scale, (screenH - offsetY) / scale))
}
