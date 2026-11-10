package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.VectraSprings
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.rgba
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class ToastType { Default, Success, Error, Warning }

/** A single button on a toast (Undo). Tapping it dismisses the toast. */
class ToastAction(val label: String, val onClick: () -> Unit)

class Toast internal constructor(
    val id: Long,
    val type: ToastType,
    val title: String,
    val description: String?,
    val action: ToastAction?,
    val durationMs: Long,
)

/**
 * App-wide toasts. One shows at a time, floating above the navbar; later ones queue
 * behind it. A newer toast of the same title replaces a queued one rather than piling up.
 */
@Stable
class Toaster {
    internal val queue = mutableStateListOf<Toast>()
    private var nextId = 0L

    fun show(title: String, description: String? = null, action: ToastAction? = null, durationMs: Long = DEFAULT_DURATION_MS) =
        push(ToastType.Default, title, description, action, durationMs)
    fun success(title: String, description: String? = null, action: ToastAction? = null, durationMs: Long = DEFAULT_DURATION_MS) =
        push(ToastType.Success, title, description, action, durationMs)
    fun error(title: String, description: String? = null, action: ToastAction? = null, durationMs: Long = DEFAULT_DURATION_MS) =
        push(ToastType.Error, title, description, action, durationMs)
    fun warning(title: String, description: String? = null, action: ToastAction? = null, durationMs: Long = DEFAULT_DURATION_MS) =
        push(ToastType.Warning, title, description, action, durationMs)

    private fun push(type: ToastType, title: String, description: String?, action: ToastAction?, durationMs: Long) {
        queue.removeAll { it !== queue.firstOrNull() && it.title == title }
        queue.add(Toast(nextId++, type, title, description, action, durationMs))
        // Only an actionable toast is worth waiting behind; plain ones give way immediately.
        if (queue.size > 1 && queue.first().action == null) queue.removeAt(0)
    }

    internal fun dismiss(toast: Toast) {
        queue.remove(toast)
    }

    companion object {
        const val DEFAULT_DURATION_MS = 4000L
    }
}

val LocalToaster = staticCompositionLocalOf { Toaster() }

/** Hosts the current toast at the bottom of the window, [bottomOffset] dp above the system bar. */
@Composable
fun ToasterHost(toaster: Toaster, modifier: Modifier = Modifier, bottomOffset: Int = 100) {
    val safeBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val current = toaster.queue.firstOrNull()
    Box(
        modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = bottomOffset.dp + safeBottom),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (current != null) key(current.id) { ToastBanner(current, toaster) }
    }
}

/** The banner's exit in progress, if any; [onTimer] when it is the lifetime running out. */
private class LeaveState {
    var job: Job? = null
    var onTimer = false
}

/** How a screen reader announces a toast: errors interrupt, the rest wait their turn. */
internal fun toastLiveRegion(type: ToastType): LiveRegionMode =
    if (type == ToastType.Error) LiveRegionMode.Assertive else LiveRegionMode.Polite

private const val DismissDistanceDp = 64f
private const val DismissVelocity = 900f

@Composable
private fun ToastBanner(toast: Toast, toaster: Toaster) {
    val c = Vectra.colors
    val haptics = Vectra.haptics
    val reduceMotion = Vectra.reduceMotion
    val scope = rememberCoroutineScope()
    val enter = remember { Animatable(0f) }
    val dragX = remember { Animatable(0f) }
    val dragY = remember { Animatable(0f) }
    var height by remember { mutableStateOf(0f) }
    var held by remember { mutableStateOf(false) }
    val leaving = remember { LeaveState() }

    // The exit runs in the banner's own scope, not in an effect a touch restarts: a cancelled exit
    // used to leave the toast half-faded at the head of the queue, blocking every later toast.
    fun leave(towardsX: Float = 0f, onTimer: Boolean = false) {
        if (leaving.job?.isActive == true) return
        leaving.onTimer = onTimer
        leaving.job = scope.launch {
            coroutineScope {
                if (towardsX != 0f) launch { dragX.animateTo(towardsX, tween(180, easing = VectraEasing.Fade)) }
                enter.animateTo(0f, if (reduceMotion) tween(120) else tween(200, easing = VectraEasing.Fade))
            }
            toaster.dismiss(toast)
        }
    }

    LaunchedEffect(Unit) {
        when (toast.type) {
            ToastType.Error -> haptics.reject()
            ToastType.Success -> haptics.confirm()
            else -> Unit
        }
        enter.animateTo(1f, if (reduceMotion) tween(150) else VectraSprings.pop())
    }
    // The lifetime pauses while a finger rests on the toast.
    LaunchedEffect(held) {
        if (held) {
            // A finger landed while the toast was fading on its own (reaching for Undo): bring it back.
            if (leaving.onTimer && leaving.job?.isActive == true) {
                leaving.job?.cancel()
                enter.animateTo(1f, tween(120))
            }
            return@LaunchedEffect
        }
        delay(toast.durationMs)
        leave(onTimer = true)
    }

    val (icon, tint) = when (toast.type) {
        ToastType.Success -> RemixIcons.CheckboxCircleFill to c.tints.emerald.text
        ToastType.Error -> RemixIcons.ErrorWarningFill to c.tints.red.text
        ToastType.Warning -> RemixIcons.AlertFill to c.tints.amber.text
        ToastType.Default -> null to c.textPrimary
    }
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .onSizeChanged { height = it.height.toFloat() }
            .graphicsLayer {
                val e = enter.value
                alpha = e.coerceIn(0f, 1f) * (1f - abs(dragX.value) / (size.width.coerceAtLeast(1f)))
                translationX = dragX.value
                translationY = dragY.value + (1f - e) * (if (reduceMotion) 0f else height * 0.6f)
                val s = if (reduceMotion) 1f else 0.94f + 0.06f * e
                scaleX = s; scaleY = s
            }
            // One node read out as it appears (errors interrupt), with a dismiss action standing in
            // for the swipe; the Undo button stays its own node.
            .semantics(mergeDescendants = true) {
                liveRegion = toastLiveRegion(toast.type)
                dismiss { leave(); true }
            }
            .pointerInput(toast.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    held = true
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    held = false
                }
            }
            .pointerInput(toast.id) {
                val velocity = VelocityTracker()
                val dismissPx = DismissDistanceDp.dp.toPx()
                detectDragGestures(
                    onDragStart = { velocity.resetTracking() },
                    onDragEnd = {
                        val v = velocity.calculateVelocity()
                        scope.launch {
                            when {
                                abs(dragX.value) > dismissPx || abs(v.x) > DismissVelocity -> leave(towardsX = if (dragX.value + v.x >= 0f) size.width.toFloat() else -size.width.toFloat())
                                dragY.value > dismissPx / 2 || v.y > DismissVelocity -> leave()
                                else -> {
                                    launch { dragX.animateTo(0f, VectraSprings.drag()) }
                                    dragY.animateTo(0f, VectraSprings.drag())
                                }
                            }
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    velocity.addPosition(change.uptimeMillis, change.position)
                    scope.launch {
                        dragX.snapTo(dragX.value + amount.x)
                        // Down dismisses; up is resisted.
                        dragY.snapTo((dragY.value + if (amount.y > 0f || dragY.value > 0f) amount.y else amount.y * 0.2f).coerceAtLeast(-12f))
                    }
                }
            }
            .boxShadow(shape, listOf(BoxShadow(y = 8f, blur = 24f, color = rgba(0, 0, 0, 0.18f))))
            .background(c.surfaceSecondary, shape)
            .border(1.dp, c.borderSubtle, shape)
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = if (toast.action != null) 8.dp else 16.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) VIcon(icon, size = 20.dp, tint = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(toast.title, style = vtext(TypeScale.Body, Weight.Semibold), color = c.textPrimary)
            if (toast.description != null) VText(toast.description, style = vtext(TypeScale.Caption), color = c.textSecondary)
        }
        toast.action?.let { action ->
            Box(
                Modifier
                    .heightIn(min = 36.dp)
                    .background(c.accentPrimary.copy(alpha = 0.15f), CircleShape)
                    .tappable(CircleShape, haptic = { tick() }) {
                        action.onClick()
                        leave()
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                VText(action.label, style = vtext(TypeScale.Body, Weight.Bold), color = c.accentPrimary)
            }
        }
    }
}
