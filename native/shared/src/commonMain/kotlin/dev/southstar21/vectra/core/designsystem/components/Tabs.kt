package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.LocalContentColor
import dev.southstar21.vectra.core.designsystem.theme.LocalTextStyle
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraSprings
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.navPillGlow
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.topSheen
import dev.southstar21.vectra.core.designsystem.theme.vtext
import kotlin.math.roundToInt
import dev.southstar21.vectra.core.designsystem.theme.tappable

/**
 * A glass well with one accent pill that springs between the measured
 * triggers (position springs, size applies outright). `columns` lays the triggers
 * out as a grid that wraps (PillRadio).
 */
@Composable
fun <T> VTabs(
    selected: T,
    options: List<T>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int? = null,
    triggerHeight: Dp? = null,
    textSize: Pair<TextUnit, TextUnit> = TypeScale.Caption,
    pillSpec: AnimationSpec<Offset> = VectraSprings.snappy(),
    // Most tab rows replace the spring under reduced motion; the settings pills keep it.
    pillHonorsReduceMotion: Boolean = true,
    label: @Composable (option: T, selected: Boolean) -> Unit,
) {
    val c = Vectra.colors
    val density = LocalDensity.current
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    val container = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val reduceMotion = Vectra.reduceMotion && pillHonorsReduceMotion
    val haptics = Vectra.haptics
    val selectedIndex = options.indexOf(selected)
    val target = bounds[selectedIndex]
    val pos = remember { Animatable(Offset.Unspecified, Offset.VectorConverter) }
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        if (pos.value == Offset.Unspecified || reduceMotion) pos.snapTo(t.topLeft)
        else pos.animateTo(t.topLeft, pillSpec)
    }
    val rows = if (columns == null) 1 else (options.size + columns - 1) / columns
    val shape: Shape = if (rows > 1) RoundedCornerShape(22.dp) else CircleShape
    Box(
        modifier
            // One row is 40dp tall (growing with large text); otherwise the triggers fill
            // whatever height the parent allows (a sheet header stretched to the whole sheet).
            .then(if (rows == 1 && triggerHeight == null) Modifier.heightIn(min = 40.dp).height(IntrinsicSize.Min) else Modifier)
            .glass(shape)
            .padding(4.dp),
    ) {
      Box(Modifier.onGloballyPositioned { container.value = it }) {
        if (target != null && pos.value != Offset.Unspecified) {
            with(density) {
                Box(
                    Modifier
                        .offset { IntOffset(pos.value.x.roundToInt(), pos.value.y.roundToInt()) }
                        .size(target.width.toDp(), target.height.toDp())
                        .boxShadow(CircleShape, navPillGlow(c.accentPrimary))
                        .background(c.accentPrimary, CircleShape)
                        .border(1.dp, c.accentPrimary.copy(alpha = 0.5f), CircleShape)
                        .topSheen(),
                )
            }
        }
        val trigger: @Composable (Int, Modifier) -> Unit = { i, m ->
            val option = options[i]
            val isSelected = i == selectedIndex
            val interaction = remember { MutableInteractionSource() }
            val ink by animateColorAsState(if (isSelected) Color.White else c.textSecondary, tween(200), label = "tabInk")
            Box(
                m
                    .then(if (triggerHeight != null) Modifier.heightIn(min = triggerHeight) else Modifier.fillMaxHeight())
                    .onGloballyPositioned { coords -> container.value?.let { bounds[i] = it.localBoundingBoxOf(coords) } }
                    .pressScale(interaction, 0.97f)
                    .tappable(role = Role.Tab, highlight = false, interactionSource = interaction) {
                        if (!isSelected) haptics.tick()
                        onSelect(option)
                    }
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = if (columns != null) 8.dp else 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides ink,
                    LocalTextStyle provides vtext(textSize, if (isSelected) Weight.Bold else Weight.Semibold),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        label(option, isSelected)
                    }
                }
            }
        }
        if (columns == null) {
            Row(Modifier.fillMaxHeight()) {
                options.indices.forEach { trigger(it, Modifier.weight(1f)) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (r in 0 until rows) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (col in 0 until columns) {
                            val i = r * columns + col
                            if (i < options.size) {
                                Box(Modifier.weight(1f)) { trigger(i, Modifier.fillMaxWidth()) }
                            } else {
                                Box(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
      }
    }
}

/** Plain-text tabs (the common case). */
@Composable
fun <T> VTabs(
    selected: T,
    options: List<T>,
    onSelect: (T) -> Unit,
    labelOf: (T) -> String,
    modifier: Modifier = Modifier,
) = VTabs(selected, options, onSelect, modifier) { o, _ -> VText(labelOf(o), maxLines = 1) }
