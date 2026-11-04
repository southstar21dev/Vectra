package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** False while the page is a hidden (kept-alive) tab or covered by a pushed screen: loops and clocks freeze. */
val LocalPageActive = compositionLocalOf { true }

/**
 * The lifecycle a map on this page follows, when that differs from the page's own: a tab
 * under a pushed screen stops polling, but a stopped map comes back blank on pop.
 */
val LocalMapLifecycleOwner = compositionLocalOf<LifecycleOwner?> { null }

/** Frame cap for small decorative loops: nobody sees 120Hz on a 12dp pulse, but the battery does. */
const val DecorativeLoopFps = 24

/**
 * Epoch millis, re-read on each [intervalMs] boundary while the page is on screen and
 * the app is started; a page coming back gets a fresh value at once.
 */
@Composable
fun rememberPageClock(intervalMs: Long, nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }): Long {
    var now by remember { mutableLongStateOf(nowMillis()) }
    val active = LocalPageActive.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(intervalMs, active, lifecycle) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = nowMillis()
                delay(intervalMs - now % intervalMs)
            }
        }
    }
    return now
}

/** Holds whether a decorative element is in the window, for `rememberLoop(enabled = …)`. */
fun Modifier.trackOnScreen(onScreen: MutableState<Boolean>): Modifier =
    onVisibilityChanged(minFractionVisible = 0.01f) { onScreen.value = it }

/** Starts true: a loop begins at once and stops on the first report that it is off screen. */
@Composable
fun rememberOnScreen(): MutableState<Boolean> = remember { mutableStateOf(true) }

/**
 * A repeating 0→1 progress for decorative loops (spinners, pulses, shimmers).
 * It only ticks while the page is visible and motion is allowed. Read `.value`
 * inside draw / graphicsLayer lambdas so each tick redraws instead of recomposing.
 */
@Composable
fun rememberLoop(
    periodMs: Int,
    easing: Easing = LinearEasing,
    reverse: Boolean = false,
    delayMs: Int = 0,
    enabled: Boolean = true,
    /** Caps how often the value changes; null follows the display. */
    maxFps: Int? = null,
): State<Float> {
    val state = remember { mutableFloatStateOf(0f) }
    val run = enabled && LocalPageActive.current
    LaunchedEffect(run, periodMs, reverse, maxFps) {
        if (!run) return@LaunchedEffect
        val start = withInfiniteAnimationFrameMillis { it } - delayMs
        while (true) {
            withInfiniteAnimationFrameMillis { now ->
                val t = ((now - start).coerceAtLeast(0) % (if (reverse) periodMs * 2L else periodMs.toLong())).toFloat()
                val p = if (!reverse) t / periodMs else if (t <= periodMs) t / periodMs else 2f - t / periodMs
                state.floatValue = easing.transform(p.coerceIn(0f, 1f))
            }
            if (maxFps != null) delay(1000L / maxFps)
        }
    }
    return state
}
