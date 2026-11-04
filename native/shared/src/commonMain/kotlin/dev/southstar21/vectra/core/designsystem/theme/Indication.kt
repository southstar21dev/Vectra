package dev.southstar21.vectra.core.designsystem.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * No indication at all. For surfaces that report presses for their own reasons, like the map's
 * input layer, where the app's [PressHighlight] would wash the whole map on the first touch.
 */
object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0
}

/**
 * Press highlight: a tint over the pressed element in its own [shape], so it never spills
 * past rounded corners. It appears immediately and fades out on release, which is what
 * makes a tap feel acknowledged under the finger.
 */
class PressHighlight(private val color: Color, private val shape: Shape = RectangleShape) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressHighlightNode(interactionSource, color, shape)

    override fun equals(other: Any?) = other is PressHighlight && other.color == color && other.shape == shape
    override fun hashCode() = 31 * color.hashCode() + shape.hashCode()
}

private class PressHighlightNode(
    private val source: InteractionSource,
    private val color: Color,
    private val shape: Shape,
) : Modifier.Node(), DrawModifierNode {
    private val alpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var presses = 0
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses++
                    is PressInteraction.Release, is PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
                }
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
        if (a > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), color.copy(alpha = color.alpha * a))
    }
}
