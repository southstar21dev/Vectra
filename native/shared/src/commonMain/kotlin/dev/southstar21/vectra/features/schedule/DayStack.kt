package dev.southstar21.vectra.features.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.Dp
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.components.VIcon
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.specularBorder
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.preferences.TimeFormat
import kotlin.math.roundToInt

private const val StackCardDp = 50
private const val StackGapDp = 6
private const val StackPeekDp = 7

private fun mix(a: Float, b: Float, f: Float) = a + (b - a) * f

/** How one small card looks at this point of the fold: background, content and subtitle fades. */
private class SmallCardLook(val bg: Color, val contentAlpha: Float, val subtitleAlpha: Float, val enabled: Boolean)

@Composable
private fun SmallCard(
    entry: DayEntry,
    status: AttendanceStatus?,
    subtitle: String?,
    look: SmallCardLook,
    d: DayColors,
    timeFormat: TimeFormat,
    tapLabel: String,
    onDisc: (() -> Unit)?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val square = entry is DayEntry.Item && entry.occurrence.item.isTask
    Row(
        modifier
            .fillMaxWidth()
            .height(StackCardDp.dp)
            .boxShadow(SmallShape, d.smallShadow)
            .background(look.bg, SmallShape)
            .specularBorder(SmallShape, d.border, d.smallBorderTop)
            .tappable(SmallShape, enabled = look.enabled, role = Role.Button, onClickLabel = tapLabel, onClick = onTap)
            .padding(start = 13.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fade = Modifier.graphicsLayer { alpha = look.contentAlpha.coerceIn(0f, 1f) }
        val discTap = if (onDisc != null) {
            Modifier.tappable(RoundedCornerShape(50), enabled = look.enabled, role = Role.Button, onClickLabel = "Change attendance for ${entry.title}", onClick = onDisc)
        } else Modifier
        SmallStatusDisc(status, d.dashed, fade.then(discTap), square = square)
        Column(fade.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            VText(entry.title, style = vtext(14.sp to 17.sp, Weight.Bold), color = d.room, maxLines = 1)
            if (subtitle != null && look.subtitleAlpha > 0.01f) {
                VText(subtitle, Modifier.graphicsLayer { alpha = look.subtitleAlpha.coerceIn(0f, 1f) }, vtext(11.sp to 13.sp, Weight.Semibold), d.muted, maxLines = 1)
            }
        }
        VText(rangeLabel(entry.start, entry.end, timeFormat), fade, vtext(12.sp to 16.sp, Weight.Bold, mono = true).tnum(), d.muted, maxLines = 1)
    }
}

/**
 * What the stack does with each finished entry: its status disc, what a disc tap does, and what
 * tapping the card does once the stack is open (its Course Hub, or the item's editor).
 */
internal class StackActions(
    val statusOf: (DayEntry) -> AttendanceStatus?,
    val onDisc: (DayEntry) -> (() -> Unit)?,
    val onOpen: (DayEntry) -> (() -> Unit)? = { null },
) {
    /** What tapping [e] does: opens it when [opens] and it can be opened, else [fold]s or fans the stack. */
    fun tap(e: DayEntry, opens: Boolean, fold: () -> Unit): () -> Unit = (if (opens) onOpen(e) else null) ?: fold
}

/**
 * Finished classes as a stack. Every card stays composed and one spring ([open] 0 → 1) moves
 * each of them between its stacked spot and its place in the list, so fanning out and folding
 * are the same continuous motion, played forwards or backwards (and can reverse mid-way).
 * Folded, the newest sits in front and up to two older cards peek out below it, narrower.
 */
@Composable
internal fun FinishedStack(
    finished: List<DayEntry>,
    open: Boolean,
    d: DayColors,
    timeFormat: TimeFormat,
    actions: StackActions,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = Vectra.reduceMotion
    val n = finished.size
    val fan by animateFloatAsState(
        if (open) 1f else 0f,
        if (reduceMotion) tween(150) else spring(dampingRatio = 0.86f, stiffness = 300f),
        label = "stackFan",
    )
    val label = "$n finished ${if (n == 1) "item" else "items"}. ${if (open) "Tap to stack them" else "Tap to show them"}"
    Column(modifier.fillMaxWidth()) {
        // Open, the cards open their own course; this folds them back.
        // One card is no stack: nothing to fold, and a tap opens it at once.
        if (n > 1 && fan > 0.01f) FoldPill(onToggle, (FoldPillDp * fan.coerceIn(0f, 1f)).dp, Modifier.align(Alignment.CenterHorizontally).graphicsLayer { alpha = fan.coerceIn(0f, 1f) })
        Layout(
            modifier = Modifier.fillMaxWidth(),
            measurePolicy = stackMeasurePolicy(n, fan),
            content = {
                finished.forEachIndexed { i, e ->
                    key(e.key) {
                        StackedCard(e, depth = n - 1 - i, total = n, fan = { fan }, open, d, timeFormat, actions, label, onToggle)
                    }
                }
            },
        )
    }
}

/** One card of the stack at its depth (0 is the newest, in front; 1 and 2 peek out below it). */
@Composable
private fun StackedCard(
    e: DayEntry,
    depth: Int,
    total: Int,
    fan: () -> Float,
    open: Boolean,
    d: DayColors,
    timeFormat: TimeFormat,
    actions: StackActions,
    label: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val folded = depth.coerceAtMost(2)
    val edgeBg = when (folded) {
        0 -> d.card
        1 -> d.edgeNear
        else -> d.edgeFar
    }
    // Open (or a lone card), a tap opens the entry; folded, it fans the stack out.
    val opens = open || total == 1
    val tap = actions.tap(e, opens, onToggle)
    val f = fan()
    val look = SmallCardLook(
        bg = lerp(edgeBg, d.card, f.coerceIn(0f, 1f)),
        contentAlpha = if (depth == 0) 1f else f * 1.4f - 0.4f,
        subtitleAlpha = 1f - f * 2f,
        // Folded, only the front card answers; the ones behind it are out of reach.
        enabled = depth == 0 || open,
    )
    SmallCard(
        e,
        actions.statusOf(e),
        if (depth == 0 && total > 1) "and ${total - 1} earlier" else null,
        look,
        d,
        timeFormat,
        tapLabel = if (opens) "Open ${e.title}" else label,
        onDisc = actions.onDisc(e),
        onTap = tap,
        modifier = modifier
            .then(if (depth > 0 && !open) Modifier.clearAndSetSemantics { } else Modifier)
            .graphicsLayer {
                val inset = 11.dp.toPx() * folded
                scaleX = mix(1f - 2f * inset / size.width, 1f, fan())
                // Cards deeper than the two edges hide exactly behind the second one.
                if (depth > 2) alpha = fan().coerceIn(0f, 1f)
            },
    )
}

/** Room the fold pill takes above the open stack (its own height plus the gap under it). */
private const val FoldPillDp = 40

/** "Fold" over the open stack: stacks the finished cards back up. */
@Composable
private fun FoldPill(onFold: () -> Unit, height: Dp, modifier: Modifier = Modifier) {
    val d = rememberDayColors()
    Box(modifier.height(height), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .height(30.dp)
                .background(d.card, RoundedCornerShape(50))
                .specularBorder(RoundedCornerShape(50), d.border, d.smallBorderTop)
                .tappable(RoundedCornerShape(50), role = Role.Button, onClickLabel = "Stack the finished classes", onClick = onFold)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VIcon(RemixIcons.ArrowUpSLine, size = 14.dp, tint = d.muted)
            VText("Fold", style = vtext(12.sp to 16.sp, Weight.Bold), color = d.muted)
        }
    }
}

/** Places the cards between their stacked spots and their list rows, [fan] of the way. */
private fun stackMeasurePolicy(n: Int, fan: Float) = MeasurePolicy { measurables, constraints ->
    val w = constraints.maxWidth
    val cardH = StackCardDp.dp.toPx()
    val step = cardH + StackGapDp.dp.toPx()
    val peek = StackPeekDp.dp.toPx()
    val placeables = measurables.map { it.measure(Constraints.fixed(w, cardH.roundToInt())) }
    val foldedH = cardH + peek * (n - 1).coerceAtMost(2)
    val openH = n * cardH + (n - 1) * StackGapDp.dp.toPx()
    layout(w, mix(foldedH, openH, fan).roundToInt().coerceAtLeast(cardH.roundToInt())) {
        placeables.forEachIndexed { i, p ->
            val depth = n - 1 - i
            // Open, the list reads oldest to newest, top to bottom.
            p.place(0, mix(peek * depth.coerceAtMost(2), i * step, fan).roundToInt(), zIndex = -depth.toFloat())
        }
    }
}
