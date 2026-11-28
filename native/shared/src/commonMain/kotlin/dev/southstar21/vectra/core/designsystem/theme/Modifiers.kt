package dev.southstar21.vectra.core.designsystem.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The standard tap target: a [PressHighlight] clipped to [shape], an optional press scale and
 * an optional haptic on click. Prefer this over a bare `clickable` so every tap in the app
 * answers the same way.
 *
 * Built from modifier nodes, not `composed`: the highlight and the scale are one indication
 * ([TapIndication]) that reads the theme colour through a composition local, and the haptics
 * are looked up from the same locals when a click lands.
 */
internal fun Modifier.tappable(
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
): Modifier {
    val scale = if (enabled && pressedScale < 1f) pressedScale else 1f
    val indication = if (highlight || scale < 1f) TapIndication(shape, highlight, scale) else null
    val haptics = if (haptic != null || onLongClick != null) HapticsLookup() else null
    return (if (haptics != null) this then HapticsLookupElement(haptics) else this)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = indication,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onLongClick = onLongClick?.let { long -> { haptics?.get()?.longPress(); long() } },
            hapticFeedbackEnabled = false,
        ) {
            if (haptic != null) haptics?.get()?.let(haptic)
            onClick()
        }
}

/** Hands a click handler the [VectraHaptics] of the place the modifier sits in. */
private class HapticsLookup {
    var node: HapticsLookupNode? = null
    fun get(): VectraHaptics? = node?.currentValueOf(LocalVectraHaptics)
}

private class HapticsLookupElement(val lookup: HapticsLookup) : ModifierNodeElement<HapticsLookupNode>() {
    override fun create() = HapticsLookupNode(lookup)
    override fun update(node: HapticsLookupNode) = node.use(lookup)
    override fun equals(other: Any?) = other is HapticsLookupElement && other.lookup === lookup
    override fun hashCode() = lookup.hashCode()
}

private class HapticsLookupNode(private var lookup: HapticsLookup) : Modifier.Node(), CompositionLocalConsumerModifierNode {
    override fun onAttach() = use(lookup)
    fun use(next: HapticsLookup) {
        if (lookup.node === this) lookup.node = null
        lookup = next
        lookup.node = this
    }
    override fun onDetach() {
        if (lookup.node === this) lookup.node = null
    }
}

/**
 * [tappable]'s indication: the press tint in the theme's colour and, when [pressedScale] is below 1,
 * the press scale, both on the interaction source the clickable reports to.
 */
private class TapIndication(val shape: Shape, val highlight: Boolean, val pressedScale: Float) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = TapIndicationNode(interactionSource, shape, highlight, pressedScale)
    override fun equals(other: Any?) =
        other is TapIndication && other.shape == shape && other.highlight == highlight && other.pressedScale == pressedScale
    override fun hashCode() = 31 * (31 * shape.hashCode() + highlight.hashCode()) + pressedScale.hashCode()
}

private class TapIndicationNode(source: InteractionSource, shape: Shape, highlight: Boolean, pressedScale: Float) : DelegatingNode() {
    init {
        // The scale wraps the tint, so the tint scales with the element like it did under pressScale.
        if (pressedScale < 1f) delegate(PressScaleNode(source, pressedScale, DefaultPressDurationMs))
        if (highlight) delegate(ThemedPressHighlightNode(source, shape))
    }
}

/** Like [PressHighlight], but the tint is the current theme's, read when it draws. */
private class ThemedPressHighlightNode(private val source: InteractionSource, private val shape: Shape) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val alpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var presses = 0
            source.interactions.collect { interaction ->
                presses = pressesAfter(presses, interaction) ?: return@collect
                val pressed = presses > 0
                launch {
                    // A quick tap releases before the highlight lands; finish showing it first.
                    if (pressed || alpha.value < 1f) alpha.animateTo(1f, tween(((1f - alpha.value) * 60).toInt()))
                    if (!pressed) alpha.animateTo(0f, tween(220, easing = VectraEasing.Fade))
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = alpha.value
        if (a > 0f) {
            val color = currentValueOf(LocalVectraColors).pressOverlay
            drawOutline(shape.createOutline(size, layoutDirection, this), color.copy(alpha = color.alpha * a))
        }
    }
}

/**
 * For surfaces that must swallow touches rather than answer them: scrims (with [onClick] to
 * dismiss) and panels sitting over something tappable. Everything else uses [tappable].
 *
 * Touches never reach what lies underneath, even while [enabled] is false (a closing scrim);
 * [enabled] only gates [onClick]. Unlike a `clickable` this adds nothing to the accessibility
 * tree: a scrim isn't an unlabelled button, and a panel's contents stay separate nodes instead of
 * merging into one. Surfaces offer their dismissal to screen readers as a `dismiss` action.
 */
internal fun Modifier.consumeTouches(enabled: Boolean = true, onClick: () -> Unit = {}): Modifier =
    this then ConsumeTouchesElement(enabled, onClick)

private class ConsumeTouchesElement(val enabled: Boolean, val onClick: () -> Unit) : ModifierNodeElement<ConsumeTouchesNode>() {
    override fun create() = ConsumeTouchesNode(enabled, onClick)
    override fun update(node: ConsumeTouchesNode) {
        node.enabled = enabled
        node.onClick = onClick
    }
    override fun equals(other: Any?) = other is ConsumeTouchesElement && other.enabled == enabled && other.onClick === onClick
    override fun hashCode() = 31 * enabled.hashCode() + onClick.hashCode()
}

/** A pointer-input node with no semantics: it takes the hit (so siblings below get nothing) and runs taps. */
private class ConsumeTouchesNode(var enabled: Boolean, var onClick: () -> Unit) : DelegatingNode() {
    init {
        // The fields are read when a tap lands, so updates never restart a gesture in progress.
        delegate(SuspendingPointerInputModifierNode { detectTapGestures(onTap = { if (enabled) onClick() }) })
    }
}

/** Outer layers of a shadow stack; paint below the background. */
internal fun Modifier.boxShadow(shape: Shape, shadows: List<BoxShadow>): Modifier {
    var m = this
    for (s in shadows.filter { !it.inset }) {
        m = m.dropShadow(shape, Shadow(s.blur.dp, s.color, s.spread.dp, DpOffset(s.x.dp, s.y.dp)))
    }
    return m
}

/** Inset layers of a shadow stack; apply after the background. */
internal fun Modifier.insetShadow(shape: Shape, shadows: List<BoxShadow>): Modifier {
    var m = this
    for (s in shadows.filter { it.inset }) {
        m = m.innerShadow(shape, Shadow(s.blur.dp, s.color, s.spread.dp, DpOffset(s.x.dp, s.y.dp)))
    }
    return m
}

private const val DefaultPressDurationMs = 150

/**
 * Shrinks the element while pressed and springs it back on release, so the release carries
 * a little bounce. `durationMs = 0` snaps the press with no animation.
 */
internal fun Modifier.pressScale(interactionSource: MutableInteractionSource, scale: Float = 0.95f, durationMs: Int = DefaultPressDurationMs): Modifier =
    this then PressScaleElement(interactionSource, scale, durationMs)

private class PressScaleElement(val source: InteractionSource, val scale: Float, val durationMs: Int) : ModifierNodeElement<PressScaleNode>() {
    override fun create() = PressScaleNode(source, scale, durationMs)
    override fun update(node: PressScaleNode) = node.update(source, scale, durationMs)
    override fun equals(other: Any?) = other is PressScaleElement && other.source == source && other.scale == scale && other.durationMs == durationMs
    override fun hashCode() = 31 * (31 * source.hashCode() + scale.hashCode()) + durationMs
}

private class PressScaleNode(private var source: InteractionSource, private var scale: Float, private var durationMs: Int) :
    Modifier.Node(), LayoutModifierNode {
    private val factor = Animatable(1f)
    private var watching: Job? = null

    override fun onAttach() = watch()

    override fun onDetach() {
        watching?.cancel()
        watching = null
    }

    fun update(source: InteractionSource, scale: Float, durationMs: Int) {
        val restart = source != this.source
        this.source = source
        this.scale = scale
        this.durationMs = durationMs
        if (restart) watch()
    }

    private fun watch() {
        watching?.cancel()
        watching = coroutineScope.launch {
            var presses = 0
            source.interactions.collect { interaction ->
                presses = pressesAfter(presses, interaction) ?: return@collect
                val pressed = presses > 0
                val spec: AnimationSpec<Float> = when {
                    durationMs == 0 -> snap()
                    pressed -> tween(durationMs / 2, easing = VectraEasing.Fade)
                    else -> spring(0.6f, 600f)
                }
                // A new press or release replaces the running animation.
                launch { factor.animateTo(if (pressed) scale else 1f, spec) }
            }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = factor.value
                scaleY = factor.value
            }
        }
    }
}

/** A 1dp highlight along the top edge that fades out towards both ends. */
internal fun Modifier.topSheen(color: Color = Color.White.copy(alpha = 0.5f), insetX: Float = 8f): Modifier = drawWithContent {
    drawContent()
    val inset = insetX.dp.toPx()
    drawRect(
        brush = Brush.horizontalGradient(listOf(Color.Transparent, color, Color.Transparent), startX = inset, endX = size.width - inset),
        topLeft = Offset(inset, 0f),
        size = Size(size.width - inset * 2, 1.dp.toPx()),
    )
}

/** Negative margins: lay out wider/taller than the parent slot. */
internal fun Modifier.bleed(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val h = horizontal.roundToPx()
    val v = vertical.roundToPx()
    val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * h else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxW))
    val w = (placeable.width - 2 * h).coerceAtLeast(0)
    val ht = (placeable.height - 2 * v).coerceAtLeast(0)
    layout(w, ht) { placeable.place(-h, -v) }
}
