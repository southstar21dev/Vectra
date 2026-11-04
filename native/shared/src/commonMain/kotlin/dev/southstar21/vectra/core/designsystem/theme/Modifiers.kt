package dev.southstar21.vectra.core.designsystem.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * The standard tap target: a [PressHighlight] clipped to [shape], an optional press scale and
 * an optional haptic on click. Prefer this over a bare `clickable` so every tap in the app
 * answers the same way.
 */
fun Modifier.tappable(
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    pressedScale: Float = 1f,
    highlight: Boolean = true,
    haptic: (VectraHaptics.() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    /** A long press; plays the long-press haptic before running. */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val haptics = Vectra.haptics
    val overlay = Vectra.colors.pressOverlay
    val indication = remember(overlay, shape, highlight) { if (highlight) PressHighlight(overlay, shape) else null }
    (if (pressedScale < 1f && enabled) Modifier.pressScale(source, pressedScale) else Modifier)
        .combinedClickable(
            interactionSource = source,
            indication = indication,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onLongClick = onLongClick?.let { long -> { haptics.longPress(); long() } },
            hapticFeedbackEnabled = false,
        ) {
            haptic?.invoke(haptics)
            onClick()
        }
}

/**
 * For surfaces that must swallow touches rather than answer them: scrims (with [onClick] to
 * dismiss) and panels sitting over something tappable. Everything else uses [tappable].
 */
fun Modifier.consumeTouches(enabled: Boolean = true, onClick: () -> Unit = {}): Modifier =
    clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick)

/** Outer layers of a shadow stack; paint below the background. */
fun Modifier.boxShadow(shape: Shape, shadows: List<BoxShadow>): Modifier {
    var m = this
    for (s in shadows.filter { !it.inset }) {
        m = m.dropShadow(shape, Shadow(s.blur.dp, s.color, s.spread.dp, DpOffset(s.x.dp, s.y.dp)))
    }
    return m
}

/** Inset layers of a shadow stack; apply after the background. */
fun Modifier.insetShadow(shape: Shape, shadows: List<BoxShadow>): Modifier {
    var m = this
    for (s in shadows.filter { it.inset }) {
        m = m.innerShadow(shape, Shadow(s.blur.dp, s.color, s.spread.dp, DpOffset(s.x.dp, s.y.dp)))
    }
    return m
}

/**
 * Shrinks the element while pressed and springs it back on release, so the release carries
 * a little bounce. `durationMs = 0` snaps the press with no animation.
 */
fun Modifier.pressScale(interactionSource: MutableInteractionSource, scale: Float = 0.95f, durationMs: Int = 150): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val spec = when {
        durationMs == 0 -> snap<Float>()
        pressed -> tween<Float>(durationMs / 2, easing = VectraEasing.Fade)
        else -> spring<Float>(0.6f, 600f)
    }
    val s by animateFloatAsState(if (pressed) scale else 1f, spec, label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** A 1dp highlight along the top edge that fades out towards both ends. */
fun Modifier.topSheen(color: Color = Color.White.copy(alpha = 0.5f), insetX: Float = 8f): Modifier = drawWithContent {
    drawContent()
    val inset = insetX.dp.toPx()
    drawRect(
        brush = Brush.horizontalGradient(listOf(Color.Transparent, color, Color.Transparent), startX = inset, endX = size.width - inset),
        topLeft = Offset(inset, 0f),
        size = Size(size.width - inset * 2, 1.dp.toPx()),
    )
}

/** Negative margins: lay out wider/taller than the parent slot. */
fun Modifier.bleed(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val h = horizontal.roundToPx()
    val v = vertical.roundToPx()
    val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * h else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxW))
    val w = (placeable.width - 2 * h).coerceAtLeast(0)
    val ht = (placeable.height - 2 * v).coerceAtLeast(0)
    layout(w, ht) { placeable.place(-h, -v) }
}

/** A negative top margin: the content moves up and the layout shrinks with it. */
fun Modifier.pullUp(amount: Dp): Modifier = layout { measurable, constraints ->
    val a = amount.roundToPx()
    val placeable = measurable.measure(constraints)
    layout(placeable.width, (placeable.height - a).coerceAtLeast(0)) { placeable.place(0, -a) }
}
