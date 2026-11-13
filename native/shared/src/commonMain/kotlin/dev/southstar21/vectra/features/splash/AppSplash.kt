package dev.southstar21.vectra.features.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.icons.VectraLogo
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.platform.platformName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Even an app that is ready at once shows the splash this long (on its own clock): the owl lands
 * and "Vectra" is revealed beside it before anything disperses. Reduced motion, with nothing to
 * wait for, only keeps it from flashing.
 */
private const val SplashMinVisibleMs = 900f
private const val ReducedMinVisibleMs = 350f
private const val REDUCED_EXIT_MS = 200L

/** The splash clock's largest step: two frames at 60 Hz. */
private const val MaxFrameStepMs = 34L

/**
 * The dotted orb over the Budapest skyline with the owl and "Vectra". The owl of the system splash
 * glides and shrinks into its place beside the wordmark while the city lights come on (on iOS,
 * whose launch screen has no owl, it fades in where Android's would be). Once the app is ready
 * (and the lockup has formed, [SplashMinVisibleMs]) everything disperses into dots over the dashboard;
 * [onPresented] fires as that starts, so the cards' reveals play underneath.
 */
@Composable
fun AppSplash(appReady: Boolean, version: String, onPresented: () -> Unit) {
    val c = Vectra.colors
    val reduce = Vectra.reduceMotion
    var minElapsed by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var elapsed by remember { mutableFloatStateOf(0f) }
    // The disperse's progress, 0..1 (null while the splash stands).
    var exit by remember { mutableStateOf<Float?>(null) }
    // Reduced motion: the whole overlay fades instead.
    val overlay = remember { Animatable(1f) }
    // Rasterising and sampling the skyline is thousands of dots of work: build it off the main thread.
    val scene by produceState<SplashScene?>(null) {
        value = withContext(Dispatchers.Default) { SplashScene() }
    }
    val lockupLayer = rememberGraphicsLayer()
    val screenDensity = LocalDensity.current
    var lockupDots by remember { mutableStateOf<List<LockupDot>>(emptyList()) }
    val dismiss = minElapsed && appReady
    val dismissNow by rememberUpdatedState(dismiss)

    LaunchedEffect(Unit) {
        // The splash's own clock: at most a frame's worth a frame. A cold start keeps the main
        // thread busy and drops frames; the owl's glide waits for them instead of skipping ahead.
        var last = withFrameMillis { it }
        var clock = 0f
        var exitAt: Float? = null
        while (!done) {
            withFrameMillis { now ->
                clock += (now - last).coerceIn(0L, MaxFrameStepMs).toFloat()
                last = now
                if (clock >= if (reduce) ReducedMinVisibleMs else SplashMinVisibleMs) minElapsed = true
                elapsed = if (reduce) STILL_FRAME_ELAPSED else clock
                // The disperse starts once the lockup has been sampled into dots.
                val canExit = !reduce && dismissNow && lockupDots.isNotEmpty()
                if (canExit && exitAt == null) exitAt = clock
                exit = exitAt?.let { ((clock - it) / DisperseExitMs).coerceIn(0f, 1f) }
                if ((exit ?: 0f) >= 1f) done = true
            }
        }
    }
    LaunchedEffect(dismiss) {
        if (!dismiss) return@LaunchedEffect
        onPresented()
        if (reduce) {
            overlay.animateTo(0f, tween(REDUCED_EXIT_MS.toInt(), easing = VectraEasing.Fade))
            done = true
        } else {
            // The lockup as it stands, sampled into dots that disperse with the city.
            lockupDots = with(screenDensity) { sampleLockup(lockupLayer.toImageBitmap()) }
        }
    }
    if (done) return

    val bg = c.surfacePrimary
    val dark = bg.luminance() <= 0.55f
    val gain = if (dark) 1f else 1.7f
    val colors = SplashColors(fg = c.textPrimary, lamp = lerp(c.accentPrimary, Color.White, 0.25f), bg = bg)
    val logo = rememberVectorPainter(VectraLogo)
    val measurer = rememberTextMeasurer()
    val shortVersion = remember(version) { "v" + version.substringBefore(' ').substringBefore('+') }
    val systemOwl = platformName == "android"

    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = overlay.value }) {
        val u = exit ?: 0f
        // The backdrop clears early in the disperse: the dots drift over the dashboard.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - smoothstep(0.08f, 0.55f, u) }.background(bg))
        Canvas(Modifier.fillMaxSize()) {
            val m = sceneTransform(size.width, size.height, density)
            drawContext.transform.translate(m.offsetX, m.offsetY)
            drawContext.transform.scale(m.scale, m.scale, Offset.Zero)
            // The dots spread from a little above the middle of the screen.
            val centerY = (size.height / 2 - m.offsetY) / m.scale - 30f
            scene?.draw(this, elapsed, exit, elapsed, m.view, colors, gain, centerY)
        }
        // The owl and the wordmark: drawn whole while the splash stands, recorded so they can be
        // sampled into dots; in the disperse they hand over to those dots in the first beat.
        Canvas(
            Modifier.fillMaxSize().drawWithContent {
                lockupLayer.record { this@drawWithContent.drawContent() }
                drawLayer(lockupLayer.also { it.alpha = 1f - smoothstep(0f, 0.18f, u) })
            },
        ) {
            val t = if (reduce) LockupSettledS else elapsed / 1000f
            drawLockup(t, measurer, shortVersion, colors.fg, c.textMuted, systemOwl) { s -> with(logo) { draw(s, colorFilter = ColorFilter.tint(colors.fg)) } }
        }
        if (exit != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawLockupDots(lockupDots, u, Offset(size.width / 2, size.height / 2 - 45.dp.toPx()), colors.fg, gain)
            }
        }
    }
}

/** Seconds into the splash when the lockup has fully arrived (reduced motion shows it so at once). */
private const val LockupSettledS = 2f

/** The owl as Android's system splash draws it: the launcher foreground's 1024 viewport at 288 dp. */
private val SystemIconSize = 288.dp

/** The owl's height in the lockup, a little above the wordmark's caps; its path spans 539 of the 1024 units. */
private val OwlHeight = 37.75.dp
private const val OwlShare = 539.45f / 1024f
private const val OwlAspect = 539.45f / 415.22f
private val OwlGap = 10.dp

/** The wordmark: 44 sp, heavy, tight; its baseline sits 39% down the screen. */
private val WordStyle = vtext(44.sp to 44.sp, Weight.Extrabold, (-0.032).em)
private const val BaselineShare = 0.392f
private val VersionStyle = vtext(10.5.sp to 14.sp, Weight.Semibold, Tracking.Widest, mono = true)

/**
 * The owl gliding from the system splash into its place beside "Vectra" (600 ms), the word
 * revealed beside it from left to right, then the short, quiet version line under them.
 */
private fun DrawScope.drawLockup(
    t: Float,
    measurer: TextMeasurer,
    version: String,
    ink: Color,
    muted: Color,
    systemOwl: Boolean,
    drawLogo: DrawScope.(Size) -> Unit,
) {
    val word = measurer.measure("Vectra", WordStyle)
    val tw = word.size.width.toFloat()
    val baseline = size.height * BaselineShare
    val cap = 44.sp.toPx() * 0.727f
    val owlW = OwlHeight.toPx() / OwlAspect
    val gap = OwlGap.toPx()
    val left = size.width / 2 - (owlW + gap + tw) / 2
    val textX = left + owlW + gap

    // The owl: its whole 1024 square, centred where the owl sits, scaled from the system splash's size.
    val g = easeInOut((t / 0.6f).coerceIn(0f, 1f))
    val from = Offset(size.width / 2, size.height / 2)
    val to = Offset(left + owlW / 2, baseline - cap / 2 + 0.8.dp.toPx())
    val square = lerpF(SystemIconSize.toPx(), OwlHeight.toPx() / OwlShare, g)
    val at = Offset(lerpF(from.x, to.x, g), lerpF(from.y, to.y, g))
    // iOS's launch screen has no owl to take over from: it fades in instead.
    val owlAlpha = if (systemOwl) 1f else ease(t / 0.15f)
    translate(at.x - square / 2, at.y - square / 2) {
        drawContext.canvas.saveLayer(Rect(0f, 0f, square, square), Paint().apply { alpha = owlAlpha })
        drawLogo(Size(square, square))
        drawContext.canvas.restore()
    }

    // "Vectra", revealed left to right as the owl lands.
    val reveal = ease((t - 0.42f) / 0.45f)
    val wordAlpha = ease((t - 0.38f) / 0.3f)
    if (reveal > 0.001f) {
        val pad = 4.dp.toPx()
        clipRect(textX - pad, 0f, textX - pad + (tw + pad * 2) * reveal, size.height) {
            drawText(word, ink, Offset(textX - 6.dp.toPx() * (1 - reveal), baseline - word.firstBaseline), alpha = wordAlpha)
        }
    }

    // The version, short and quiet.
    val ver = ease((t - 0.6f) / 0.35f)
    if (ver > 0.001f) {
        val v = measurer.measure(version, VersionStyle)
        val y = baseline + 26.dp.toPx() - v.firstBaseline + (1 - ver) * 8.dp.toPx()
        drawText(v, muted, Offset(size.width / 2 - v.size.width / 2f, y), alpha = ver * 0.55f)
    }
}

/** One dot of the sampled lockup: where it was, how opaque, and its stagger. */
private class LockupDot(val x: Float, val y: Float, val alpha: Float, val seed: Float)

/** The recorded lockup sampled every 1.5 dp into dots (only where it is drawn). */
private suspend fun Density.sampleLockup(bitmap: ImageBitmap): List<LockupDot> {
    val step = 1.5.dp.toPx()
    return withContext(Dispatchers.Default) {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.readPixels(px)
        buildList {
            var y = 0f
            while (y < h) {
                var x = 0f
                while (x < w) {
                    val a = (px[y.toInt() * w + x.toInt()] ushr 24) and 0xFF
                    if (a > 50) add(LockupDot(x, y, a / 255f, seedAt(x, y)))
                    x += step
                }
                y += step
            }
        }
    }
}

/** The lockup's dots drifting out from [from], shrinking and fading, each on its own beat. */
private fun DrawScope.drawLockupDots(dots: List<LockupDot>, u: Float, from: Offset, ink: Color, gain: Float) {
    val appear = smoothstep(0f, 0.1f, u)
    val r = 0.95.dp.toPx() * (1 - 0.55f * u)
    val unit = 1.dp.toPx()
    // Grouped by opacity: a handful of drawPoints calls a frame.
    val buckets = HashMap<Int, MutableList<Offset>>()
    for (d in dots) {
        val a = d.alpha * appear * disperseFade(u, d.seed)
        if (a < 0.02f) continue
        val dx = d.x - from.x
        val dy = d.y - from.y
        val len = hypot(dx, dy).takeIf { it > 0f } ?: 1f
        val k = disperseDrift(u, d.seed, far = 180f, base = 75f, near = 21f) * unit
        buckets.getOrPut((a * 16).toInt().coerceIn(0, 16)) { ArrayList() }.add(Offset(d.x + dx / len * k, d.y + dy / len * k))
    }
    for ((ai, pts) in buckets) {
        drawPoints(pts, PointMode.Points, ink.copy(alpha = (ai / 16f * gain).coerceAtMost(1f)), strokeWidth = r * 2, cap = StrokeCap.Round)
    }
}

private fun seedAt(x: Float, y: Float): Float {
    val h = sin(x * 3.1f * 12.9898f + y * 7.3f * 78.233f) * 43758.547f
    return h - floor(h)
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun easeInOut(x: Float): Float = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2
