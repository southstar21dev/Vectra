package dev.southstar21.vectra.features.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private const val SPLASH_MIN_VISIBLE_MS = 1000L
private const val REDUCED_EXIT_MS = 200L

/**
 * The dotted orb over the Budapest skyline with the Vectra
 * wordmark. Exits (sunrise) as soon as the app is ready, after a short floor so it never flashes;
 * [onPresented] fires as the exit starts so card reveals play underneath it.
 */
@Composable
fun AppSplash(appReady: Boolean, version: String, onPresented: () -> Unit) {
    val c = Vectra.colors
    val reduce = Vectra.reduceMotion
    val exitMs = if (reduce) REDUCED_EXIT_MS else SUNRISE_EXIT_MS.toLong()
    var minElapsed by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var elapsed by remember { mutableFloatStateOf(0f) }
    var exitElapsed by remember { mutableStateOf<Float?>(null) }
    val backdrop = remember { Animatable(1f) }
    // Reduced motion: the whole overlay fades instead (REDUCED_EXIT_MS, easeOut).
    val overlay = remember { Animatable(1f) }
    val word = remember { Animatable(0f) }
    val version_ = remember { Animatable(0f) }
    val wordExit = remember { Animatable(0f) }
    // Rasterising and sampling the skyline is ~10k dots of work: build it off the main thread.
    val scene by produceState<SplashScene?>(null) {
        value = withContext(Dispatchers.Default) { SplashScene() }
    }
    val dismiss = minElapsed && appReady
    val dismissNow by rememberUpdatedState(dismiss)

    LaunchedEffect(Unit) {
        delay(SPLASH_MIN_VISIBLE_MS)
        minElapsed = true
    }
    LaunchedEffect(Unit) {
        coroutineScope {
            // Reduced motion: a plain 150ms easeOut fade, no delay.
            val reducedIn = tween<Float>(150, easing = VectraEasing.Fade)
            launch { delay(if (reduce) 0 else 150); word.animateTo(1f, if (reduce) reducedIn else tween(500, easing = VectraEasing.Decelerate)) }
            launch { delay(if (reduce) 0 else 450); version_.animateTo(1f, if (reduce) reducedIn else tween(350, easing = VectraEasing.Decelerate)) }
        }
    }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        var exitAt: Long? = null
        while (!done) {
            withFrameMillis { now ->
                elapsed = if (reduce) STILL_FRAME_ELAPSED else (now - start).toFloat()
                if (dismissNow && exitAt == null) exitAt = now
                exitElapsed = if (reduce) null else exitAt?.let { (now - it).toFloat() }
            }
        }
    }
    LaunchedEffect(dismiss) {
        if (!dismiss) return@LaunchedEffect
        onPresented()
        coroutineScope {
            launch { backdrop.animateTo(0f, tween(350, easing = VectraEasing.Fade)) }
            if (reduce) launch { overlay.animateTo(0f, tween(REDUCED_EXIT_MS.toInt(), easing = VectraEasing.Fade)) }
            launch { wordExit.animateTo(1f, tween(if (reduce) 150 else 500, easing = VectraEasing.Decelerate)) }
            delay(exitMs)
        }
        done = true
    }
    if (done) return

    val fgColor = c.textPrimary
    val bg = c.surfacePrimary
    val gain = if (bg.luminance() > 0.55f) 1.7f else 1f
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = overlay.value }) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = backdrop.value }.background(bg))
        Canvas(Modifier.fillMaxSize()) {
            val m = sceneTransform(size.width, size.height, density)
            drawContext.transform.translate(m.offsetX, m.offsetY)
            drawContext.transform.scale(m.scale, m.scale, Offset.Zero)
            scene?.draw(this, elapsed, exitElapsed, elapsed, m.view, fgColor, bg, gain)
        }
        val h = maxHeight
        Column(Modifier.fillMaxSize().padding(bottom = h * 0.22f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            VText(
                "Vectra",
                Modifier.graphicsLayer {
                    val exit = wordExit.value
                    alpha = word.value * (1f - exit)
                    translationY = ((1f - word.value) * 16f - exit * 36f) * density * (if (reduce) 0f else 1f)
                    val s = if (reduce) 1f else 0.95f + 0.05f * word.value
                    scaleX = s; scaleY = s
                },
                vtext(48.sp to 48.sp, Weight.Black, Tracking.Tight),
                fgColor,
            )
            VText(
                "v$version",
                Modifier.padding(top = 16.dp).graphicsLayer {
                    val exit = wordExit.value
                    alpha = version_.value * (1f - exit)
                    translationY = ((1f - version_.value) * 8f - exit * 36f) * density * (if (reduce) 0f else 1f)
                },
                vtext(TypeScale.Caption, Weight.Medium, Tracking.Widest),
                c.textMuted,
            )
        }
    }
}

private val Int.sp get() = TextUnit(this.toFloat(), TextUnitType.Sp)
