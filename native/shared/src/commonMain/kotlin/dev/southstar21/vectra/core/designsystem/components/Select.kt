package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
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
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.mixTransparent
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.designsystem.theme.Radius

data class SelectOption<T>(val value: T, val label: String)

/** Glass pill showing the current choice; tapping it opens the options in a sheet. */
@Composable
fun <T> VSelect(
    value: T,
    options: List<SelectOption<T>>,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    /** Heading of the options sheet. */
    title: String? = null,
) {
    val c = Vectra.colors
    // Saveable, so the sheet stays open when Android recreates the activity (dark mode, font scale).
    var open by rememberSaveable { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val chevron by animateFloatAsState(if (open) 180f else 0f, VectraSprings.snappy(), label = "chevron")
    val lit by animateFloatAsState(if (open) 1f else 0f, tween(150, easing = VectraEasing.Fade), label = "selectOpen")
    val selected = options.firstOrNull { it.value == value } ?: options.firstOrNull()
    val shape = CircleShape

    Box(
        modifier
            .heightIn(min = 36.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .then(
                if (lit > 0f) Modifier.boxShadow(
                    shape,
                    listOf(
                        BoxShadow(spread = 1f, color = c.accentPrimary.copy(alpha = c.accentPrimary.alpha * 0.4f * lit)),
                        BoxShadow(blur = 16f, color = c.accentPrimary.mixTransparent(25f * lit)),
                    ),
                ) else Modifier,
            )
            .glass(shape, hairlineInLight = true)
            .then(if (lit > 0f) Modifier.border(1.dp, c.accentPrimary.copy(alpha = c.accentPrimary.alpha * lit), shape) else Modifier)
            .tappable(shape, enabled = enabled, role = Role.DropdownList, pressedScale = 0.97f, interactionSource = interaction) { open = true }
            .padding(start = if (icon != null) 36.dp else 16.dp, end = 36.dp, top = 8.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            VIcon(icon, Modifier.align(Alignment.CenterStart).offset(x = (-22).dp), tint = lerp(c.textMuted, c.accentPrimary, lit))
        }
        VText(selected?.label.orEmpty(), style = vtext(TypeScale.Caption, Weight.Semibold), color = c.textPrimary, maxLines = 1)
        VIcon(
            RemixIcons.ArrowDownSLine,
            Modifier.align(Alignment.CenterEnd).offset(x = 22.dp).rotate(chevron),
            size = 14.dp,
            tint = if (open) c.accentPrimary else c.textSecondary,
        )
    }

    OptionSheet(open, title, options, value, onDismiss = { open = false }) {
        open = false
        if (it != value) onChange(it)
    }
}

/** A sheet listing [options] with a check against the current [value]. */
@Composable
fun <T> OptionSheet(
    open: Boolean,
    title: String?,
    options: List<SelectOption<T>>,
    value: T,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) = OptionList(open, title, options, { it == value }, Role.RadioButton, onDismiss, onPick)

/** A sheet listing [options] with a check against each one in [values]; tapping one toggles it and leaves the sheet open. */
@Composable
fun <T> MultiOptionSheet(
    open: Boolean,
    title: String?,
    options: List<SelectOption<T>>,
    values: Set<T>,
    onDismiss: () -> Unit,
    onToggle: (T) -> Unit,
) = OptionList(open, title, options, { it in values }, Role.Checkbox, onDismiss, onToggle)

@Composable
private fun <T> OptionList(
    open: Boolean,
    title: String?,
    options: List<SelectOption<T>>,
    selected: (T) -> Boolean,
    role: Role,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    val haptics = Vectra.haptics
    VSheet(open, onDismiss) {
        if (title != null) SheetHeader(title, onDismiss)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            for (opt in options) {
                val isSelected = selected(opt.value)
                val c = Vectra.colors
                val rowShape = Radius.Row
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .semantics { this.selected = isSelected }
                        .tappable(rowShape, role = role) {
                            haptics.tick()
                            onPick(opt.value)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    VText(
                        opt.label,
                        Modifier.weight(1f),
                        vtext(TypeScale.Body, if (isSelected) Weight.Bold else Weight.Medium),
                        if (isSelected) c.accentPrimary else c.textPrimary,
                    )
                    if (isSelected) VIcon(RemixIcons.CheckFill, size = 18.dp, tint = c.accentPrimary)
                }
            }
        }
    }
}
