package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.vtext
import kotlinx.coroutines.delay
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

/**
 * One grouped card of fields. Rows are separated by hairlines —
 * pass the rows as a list and the dividers are inserted between them.
 */
@Composable
fun FormGroup(modifier: Modifier = Modifier, rows: List<@Composable () -> Unit>) {
    Column(modifier.fillMaxWidth().glass(Radius.Card)) {
        rows.forEachIndexed { i, row ->
            if (i > 0) HairlineDivider()
            row()
        }
    }
}

/** A stacked label + control row inside a FormGroup. */
@Composable
fun FormField(
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Vectra.colors
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VText(label, style = vtext(TypeScale.Caption, Weight.Semibold), color = c.textSecondary)
        content()
        when {
            error != null -> VText(error, style = vtext(TypeScale.Caption, Weight.Semibold), color = c.tints.red.text)
            hint != null -> VText(hint, style = vtext(TypeScale.Caption), color = c.textMuted)
        }
    }
}

/** A single-line row: text on the left, a switch or value on the right. */
@Composable
fun SwitchRow(label: String, modifier: Modifier = Modifier, description: String? = null, control: @Composable () -> Unit) {
    val c = Vectra.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            VText(label, style = vtext(TypeScale.Body, Weight.Semibold), color = c.textPrimary)
            if (description != null) VText(description, Modifier.padding(top = 2.dp), vtext(TypeScale.Caption), c.textMuted)
        }
        control()
    }
}

/** Segmented choice on the shared Tabs pill; wraps into rows of [columns]. */
@Composable
fun <T> PillRadio(
    value: T,
    options: List<SelectOption<T>>,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 2,
) {
    VTabs(
        selected = value,
        options = options.map { it.value },
        onSelect = onChange,
        modifier = modifier.fillMaxWidth(),
        columns = columns,
        triggerHeight = 36.dp,
        textSize = if (columns == 7) TypeScale.Caption else TypeScale.Body,
    ) { o, _ -> VText(options.first { it.value == o }.label, maxLines = 1) }
}

/**
 * A quiet destructive row: the first tap arms it, the second deletes, and it
 * disarms itself after four seconds.
 */
@Composable
fun DeleteRow(
    label: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String = "Tap again to delete",
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val c = Vectra.colors
    val haptics = Vectra.haptics
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4000)
            armed = false
        }
    }
    val shape = Radius.Card
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (armed) Palette.Rose500.copy(alpha = 0.15f) else c.surfaceOverlay, tween(150), label = "deleteRowBg")
    val border by animateColorAsState(if (armed) Palette.Rose500.copy(alpha = 0.5f) else c.borderSubtle, tween(150), label = "deleteRowBorder")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .alpha(if (enabled && !busy) 1f else 0.5f)
            .pressScale(interaction, 0.97f)
            .background(bg, shape)
            .border(1.dp, border, shape)
            .tappable(enabled = enabled && !busy, role = null, highlight = false, interactionSource = interaction) {
                if (!armed) {
                    haptics.reject()
                    armed = true
                } else {
                    haptics.confirm()
                    onConfirm()
                }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) InlineLoader(tint = c.tints.red.text)
        VText(if (armed) confirmLabel else label, style = vtext(TypeScale.Body, Weight.Bold), color = c.tints.red.text)
    }
}

/** Text field height inside sheets and forms. */
val SheetInputHeight = 44.dp
