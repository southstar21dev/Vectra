package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraHaptics
import dev.southstar21.vectra.core.designsystem.theme.VectraSprings
import dev.southstar21.vectra.core.designsystem.theme.glass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val IndicatorSize = 40.dp
/** How far the finger has to pull (after resistance) before releasing refreshes. */
private val Threshold = 80.dp
/** Where the indicator rests while the refresh runs. */
private val RestingOffset = 64.dp

@Stable
private class PullState(
    private val scope: CoroutineScope,
    private val haptics: VectraHaptics,
    private val thresholdPx: Float,
    private val restingPx: Float,
    private val refresh: () -> suspend () -> Unit,
) : NestedScrollConnection {
    val offset = Animatable(0f)
    var refreshing by mutableStateOf(false)
    private var armed = false
    /** The finger's pull, tracked synchronously; [offset] follows it (and animates on release). */
    private var pulled = 0f

    val progress: Float get() = (offset.value / thresholdPx).coerceIn(0f, 1.5f)

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // Pushing back up while the indicator is out retracts it before the list scrolls.
        if (source != NestedScrollSource.UserInput || refreshing || pulled <= 0f || available.y >= 0f) return Offset.Zero
        val consumed = maxOf(available.y, -pulled)
        pull(consumed)
        return Offset(0f, consumed)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || refreshing || available.y <= 0f) return Offset.Zero
        // Past the top: the content no longer scrolls, the pull takes over with resistance.
        pull(available.y * 0.5f)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (refreshing || pulled <= 0f) return Velocity.Zero
        release()
        return available
    }

    private fun pull(delta: Float) {
        val next = (pulled + delta).coerceAtLeast(0f)
        pulled = next
        scope.launch { offset.snapTo(next) }
        val nowArmed = next >= thresholdPx
        if (nowArmed && !armed) haptics.threshold()
        armed = nowArmed
    }

    private suspend fun release() {
        pulled = 0f
        if (!armed) {
            offset.animateTo(0f, VectraSprings.settle())
            return
        }
        armed = false
        refreshing = true
        scope.launch {
            offset.animateTo(restingPx, VectraSprings.settle())
            try {
                refresh()()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // A failed refresh leaves the cached data up; the screens surface their own errors.
            } finally {
                refreshing = false
                offset.animateTo(0f, VectraSprings.settle())
            }
        }
    }
}

/**
 * Pull-to-refresh around a vertically scrolling page. Pulling past the top brings down a
 * glass indicator; releasing past the threshold runs [onRefresh] while it spins.
 */
@Composable
fun PullToRefresh(onRefresh: suspend () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = Vectra.haptics
    val density = LocalDensity.current
    val latest by rememberUpdatedState(onRefresh)
    val state = remember(haptics, density) {
        with(density) { PullState(scope, haptics, Threshold.toPx(), RestingOffset.toPx()) { latest } }
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier.fillMaxSize().nestedScroll(state)) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = state.offset.value * 0.6f }) { content() }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = top)
                .graphicsLayer {
                    val o = state.offset.value
                    translationY = o - IndicatorSize.toPx()
                    alpha = (o / (IndicatorSize.toPx())).coerceIn(0f, 1f)
                    val s = 0.6f + 0.4f * state.progress.coerceAtMost(1f)
                    scaleX = s; scaleY = s
                }
                .size(IndicatorSize)
                .glass(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (state.refreshing) Spinner(size = 20.dp, thickness = 2.5.dp)
            else PullArc(state.progress)
        }
    }
}

@Composable
private fun PullArc(progress: Float) {
    val color = Vectra.colors.accentPrimary
    Canvas(Modifier.size(20.dp)) {
        val stroke = 2.5.dp.toPx()
        val p = progress.coerceAtMost(1f)
        drawArc(
            color = color.copy(alpha = 0.4f + 0.6f * p),
            startAngle = -90f + 270f * (progress - p),
            sweepAngle = 300f * p,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}
