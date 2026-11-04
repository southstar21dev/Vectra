package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.HairlineDivider
import dev.southstar21.vectra.core.designsystem.components.InlineLoader
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.LocalContentColor
import dev.southstar21.vectra.core.designsystem.theme.LocalTextStyle
import dev.southstar21.vectra.core.designsystem.theme.PillTone
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.pillDepth
import dev.southstar21.vectra.core.designsystem.theme.pillGlass
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

/*
 * Small pieces the tracker screens share: a clipped glass list, the docked primary button,
 * status copy and the map's bottom fade. The bars and body come from the design system.
 */

/** A glass card of rows clipped to its corners, so a selected row's tint never squares them off. */
@Composable
internal fun ClippedFormGroup(modifier: Modifier = Modifier, rows: List<@Composable () -> Unit>) {
    val shape = Radius.Card
    Box(modifier.fillMaxWidth().glass(shape)) {
        Column(Modifier.fillMaxWidth().clip(shape)) {
            rows.forEachIndexed { i, row ->
                if (i > 0) HairlineDivider()
                row()
            }
        }
    }
}

/**
 * One row of a [ClippedFormGroup] in a lazy list, where no element can wrap all the rows.
 * Each row draws the same card, over-tall and cut at the row's edges, so only the first
 * and last rows show its corners and the rows read as one piece of glass.
 */
@Composable
internal fun ClippedFormRow(first: Boolean, last: Boolean, modifier: Modifier = Modifier, row: @Composable () -> Unit) {
    val radius = 24.dp
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .drawWithContent {
                    // Sideways and past the card's ends the shadow may spill; only the seams are cut.
                    val spill = 48.dp.toPx()
                    clipRect(
                        left = -spill,
                        top = if (first) -spill else 0f,
                        right = size.width + spill,
                        bottom = if (last) size.height + spill else size.height,
                    ) { this@drawWithContent.drawContent() }
                }
                .layout { measurable, constraints ->
                    val over = radius.roundToPx() * 2
                    val above = if (first) 0 else over
                    val below = if (last) 0 else over
                    val placeable = measurable.measure(constraints.copy(minHeight = constraints.maxHeight + above + below, maxHeight = constraints.maxHeight + above + below))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, -above) }
                }
                .glass(RoundedCornerShape(radius)),
        )
        Column(
            Modifier.fillMaxWidth().clip(
                RoundedCornerShape(
                    topStart = if (first) radius else 0.dp,
                    topEnd = if (first) radius else 0.dp,
                    bottomStart = if (last) radius else 0.dp,
                    bottomEnd = if (last) radius else 0.dp,
                ),
            ),
        ) {
            if (!first) HairlineDivider()
            row()
        }
    }
}

/** The sheets' 48dp accent pill; bottom bars make it full width. */
@Composable
internal fun SheetPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    pressedScale: Float = 0.95f,
    content: @Composable RowScope.() -> Unit,
) {
    val c = Vectra.colors
    val shape = Radius.Panel
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(height)
            .then(if (enabled) Modifier.pressScale(interaction, pressedScale) else Modifier)
            .alpha(if (enabled) 1f else 0.5f)
            .then(if (enabled) Modifier.pillDepth(shape, PillTone.Accent) else Modifier.background(c.accentPrimary, shape))
            .tappable(enabled = enabled, role = Role.Button, highlight = false, interactionSource = interaction, onClick = onClick)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides Color.White,
            LocalTextStyle provides vtext(TypeScale.Body, Weight.Bold),
        ) { content() }
    }
}

/** The footer button's label, or its spinner while saving. */
@Composable
internal fun RowScope.SheetPrimaryLabel(saving: Boolean, text: String) {
    if (saving) InlineLoader(tint = Color.White)
    else VText(text, maxLines = 1)
}

/** A small outlined Retry pill. */
@Composable
internal fun SheetRetryButton(onClick: () -> Unit) {
    val c = Vectra.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .height(32.dp)
            .pressScale(interaction)
            .pillGlass(CircleShape)
            .tappable(role = Role.Button, highlight = false, interactionSource = interaction, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(RemixIcons.RefreshLine, size = 14.dp, tint = c.textPrimary)
        VText("Retry", style = vtext(TypeScale.Caption), color = c.textPrimary)
    }
}

/** The spinner inside a [ListStatus]. */
@Composable
internal fun SheetListSpinner() = InlineLoader(size = 20.dp, tint = Vectra.colors.textMuted)

/** The rounded "Tracked" chip beside a line already being tracked. */
@Composable
internal fun TrackedChip() {
    val c = Vectra.colors
    VText(
        "Tracked",
        Modifier.background(c.surfaceOverlay, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
        vtext(TypeScale.Micro, Weight.Bold),
        c.textSecondary,
        maxLines = 1,
    )
}

/** A centred, muted status line. */
@Composable
internal fun SheetMutedText(text: String, modifier: Modifier = Modifier, color: Color = Vectra.colors.textMuted) =
    VText(text, modifier, vtext(TypeScale.Body), color, align = TextAlign.Center)

/** A small footnote under a list. */
@Composable
internal fun SheetFootnote(text: String) =
    VText(text, Modifier.padding(horizontal = 4.dp), vtext(TypeScale.Caption), Vectra.colors.textMuted)

/** The error's message, or [fallback] when it has none. */
internal fun trackerErrorMessage(t: Throwable, fallback: String = "Unknown error"): String =
    t.message?.takeIf { it.isNotEmpty() } ?: fallback

/** Fades a map's lower edge into the surface below it. */
@Composable
internal fun BoxScope.MapBottomFade() {
    val c = Vectra.colors
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(48.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, c.surfaceSecondary))),
    )
}
