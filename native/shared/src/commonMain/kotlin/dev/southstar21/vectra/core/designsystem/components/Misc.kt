package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraMotion
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.plannerCard
import dev.southstar21.vectra.core.designsystem.theme.rgba
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.designsystem.theme.Radius

/** Line length of an empty state's description. */
private val EmptyStateTextWidth = 448.dp

/** True once the boot splash has cleared; reveals wait for it. */
val LocalAppPresented = staticCompositionLocalOf { true }

/**
 * One card's entrance on a page's first load: fades and rises into place, staggered by
 * reading order, held until the splash clears. It plays once; coming back to a tab
 * shows the page as it was left.
 */
@Composable
fun Reveal(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (Vectra.reduceMotion) {
        Box(modifier) { content() }
        return
    }
    val presented = LocalAppPresented.current
    val progress = rememberSaveable(saver = AnimatableSaver) { Animatable(0f) }
    LaunchedEffect(presented) {
        if (presented && progress.value < 1f) progress.animateTo(1f, VectraMotion.reveal(index))
    }
    Box(
        modifier.fadeLayer {
            val v = progress.value
            alpha = v
            translationY = (1f - v) * VectraMotion.RevealRiseDp * density
        },
    ) { content() }
}

private val AnimatableSaver = Saver<Animatable<Float, AnimationVector1D>, Float>(
    save = { it.value },
    restore = { Animatable(it) },
)

/**
 * A graphics layer grown by [bleed] on every side without changing layout. A fading layer
 * is drawn offscreen and clipped to its bounds, which would cut off the outer shadows cards
 * draw past their edges until the fade ends.
 */
fun Modifier.fadeLayer(bleed: Dp = 32.dp, block: GraphicsLayerScope.() -> Unit): Modifier = this
    .layout { measurable, constraints ->
        val b = bleed.roundToPx()
        val p = measurable.measure(constraints.offset(2 * b, 2 * b))
        layout(p.width - 2 * b, p.height - 2 * b) { p.place(-b, -b) }
    }
    .graphicsLayer(block)
    .padding(bleed)

/** A surface tile with a dashed accent frame. */
@Composable
fun PostageStamp(
    modifier: Modifier = Modifier,
    innerModifier: Modifier = Modifier,
    /** The perforation's ink; the accent by default. */
    ink: Color = Vectra.colors.accentPrimary,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Vectra.colors
    val outer = Radius.Tag
    val dash = ink.copy(alpha = 0.55f)
    Box(
        modifier
            .boxShadow(outer, listOf(BoxShadow(y = 6f, blur = 16f, color = rgba(15, 23, 42, 0.14f))))
            .background(c.surfaceSecondary, outer)
            .padding(4.dp),
    ) {
        // The frame is drawn before innerModifier so the caller's padding sits
        // inside the dashed border.
        Column(
            Modifier.drawBehind {
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    dash,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2 * w, w))),
                )
            }.then(innerModifier),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** A button an [EmptyState] offers: [primary] is the default variant, [secondary] an outline. */
class EmptyAction(val label: String, val onClick: () -> Unit, val icon: ImageVector? = null)

/**
 * What a list or page shows with nothing in it: a planner card holding a drawing ([art]) made of
 * the app's own parts, a 17/22 bold [title], a caption [body], one [primary] button and at most one
 * [secondary] outline button. No emoji, no tile, no glow.
 */
@Composable
fun EmptyState(
    art: @Composable () -> Unit,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    primary: EmptyAction? = null,
    secondary: EmptyAction? = null,
) {
    val c = Vectra.colors
    Column(
        modifier.fillMaxWidth().plannerCard().padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.padding(bottom = 6.dp)) { art() }
        VText(title, style = vtext(TypeScale.Title17, Weight.Bold), color = c.textPrimary, align = TextAlign.Center)
        VText(body, Modifier.widthIn(max = EmptyStateTextWidth), vtext(TypeScale.Meta13), c.textSecondary, align = TextAlign.Center)
        if (primary != null) VButton(primary.label, primary.onClick, Modifier.padding(top = 8.dp), icon = primary.icon)
        if (secondary != null) VButton(secondary.label, secondary.onClick, variant = ButtonVariant.Outline, icon = secondary.icon)
    }
}

/** Banner shown while the device is offline and cached data is on screen. */
@Composable
fun OfflineBanner(
    modifier: Modifier = Modifier,
    message: String = "No internet connection: operating in cached offline mode",
    badgeLabel: String = "Offline mode",
) {
    val c = Vectra.colors
    val shape = Radius.Panel
    FlowRow(
        modifier
            .padding(bottom = 16.dp)
            .fillMaxWidth()
            .glass(shape)
            .border(1.dp, c.tints.amber.border, shape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            VIcon(RemixIcons.WifiOffLine, tint = c.tints.amber.text)
            VText(message, style = vtext(TypeScale.Caption), color = c.tints.amber.text)
        }
        StatusLabel(badgeLabel, tone = StatusTone.Amber)
    }
}
