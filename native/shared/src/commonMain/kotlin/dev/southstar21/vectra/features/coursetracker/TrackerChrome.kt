package dev.southstar21.vectra.features.coursetracker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.StatusTone
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraColors
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.cardDepthShadows
import dev.southstar21.vectra.core.designsystem.theme.insetShadow
import dev.southstar21.vectra.core.designsystem.theme.mix
import dev.southstar21.vectra.core.designsystem.theme.mixTransparent
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.PillTone
import dev.southstar21.vectra.core.designsystem.theme.pillDepth
import dev.southstar21.vectra.core.designsystem.theme.pillGlass
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import dev.southstar21.vectra.core.designsystem.theme.resolve
import dev.southstar21.vectra.core.designsystem.theme.rgba
import dev.southstar21.vectra.core.designsystem.theme.specularBorder
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

/**
 * A glass card with depth: the glass fill and specular edge, the glass shadow in dark
 * mode and the deeper card shadow in light mode.
 */
@Composable
internal fun Modifier.glassDepth(shape: Shape): Modifier {
    val c = Vectra.colors
    val shadows = if (c.isDark) c.glassShadow else cardDepthShadows(false)
    return this
        .boxShadow(shape, shadows)
        .background(c.glassBg, shape)
        .insetShadow(shape, shadows)
        .specularBorder(shape, c.glassBorder, c.glassBorderSpecular)
}

/** The quiet glass button behind idle marks and steppers. */
@Composable
internal fun Modifier.markGlass(shape: Shape): Modifier {
    val c = Vectra.colors
    val shadows = listOf(
        BoxShadow(y = 2f, blur = 8f, color = rgba(0, 0, 0, 0.08f)),
        BoxShadow(y = 1f, color = rgba(255, 255, 255, 0.15f), inset = true),
    )
    val m = this.boxShadow(shape, shadows).background(c.glassBg, shape).insetShadow(shape, shadows)
    return if (c.isDark) m.specularBorder(shape, c.glassBorder, c.glassBorderSpecular) else m.border(1.dp, rgba(15, 23, 42, 0.08f), shape)
}

/** Fills of the solid attendance marks. */
internal object MarkFill {
    val Present = Color(0xFF047857)
    val Absent = Palette.Red600
    val Excused = Palette.Amber700
    val Cancelled = Palette.Slate600
}

/** A solid mark: fill, light rim and a soft coloured drop. */
internal fun Modifier.markDepth(shape: Shape, fill: Color): Modifier {
    val shadows = listOf(
        BoxShadow(y = 4f, blur = 12f, color = fill.mixTransparent(25f)),
        BoxShadow(y = 1f, color = rgba(255, 255, 255, 0.4f), inset = true),
    )
    return this
        .boxShadow(shape, shadows)
        .background(fill, shape)
        .insetShadow(shape, shadows)
        .border(1.dp, fill.mix(55f, Color.White), shape)
}

/** The text colour of each status tone. */
@Composable
internal fun StatusTone.ink(c: VectraColors = Vectra.colors): Color = when (this) {
    StatusTone.Amber -> c.tints.amber.text
    StatusTone.Blue -> c.tints.blue.text
    StatusTone.Emerald -> c.tints.emerald.text
    StatusTone.Green -> c.tints.green.text
    StatusTone.Purple -> c.tints.purple.text
    StatusTone.Red -> c.tints.red.text
    StatusTone.Sky -> c.tints.sky.text
    StatusTone.Violet -> c.tints.violet.text
    StatusTone.Neutral -> c.textMuted
}

/** A 1dp hairline across the top of the element. */
internal fun Modifier.topRule(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx())
}

/** Hairlines between list rows. */
@Composable
internal fun Divided(items: List<@Composable () -> Unit>) {
    val line = Vectra.colors.borderSubtle
    items.forEachIndexed { i, item ->
        Box(if (i > 0) Modifier.topRule(line) else Modifier) { item() }
    }
}

/** The outline "Add" pill in section headers. */
@Composable
internal fun AddPill(label: String = "Add", onClick: () -> Unit) {
    val c = Vectra.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = CircleShape
    val shadows = listOf(
        BoxShadow(y = 2f, blur = 8f, color = rgba(0, 0, 0, 0.08f)),
        BoxShadow(y = 1f, color = rgba(255, 255, 255, 0.15f), inset = true),
    )
    Row(
        Modifier
            .pressScale(interaction)
            .boxShadow(shape, shadows)
            .background(c.glassBg, shape)
            .insetShadow(shape, shadows)
            .specularBorder(shape, c.glassBorder, c.glassBorderSpecular)
            .tappable(role = null, highlight = false, interactionSource = interaction, onClick = onClick)
            .padding(horizontal = 14.dp)
            .defaultMinSize(minHeight = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(RemixIcons.AddLine, size = 14.dp, tint = c.textPrimary)
        VText(label, style = vtext(13.sp to 18.sp, Weight.Bold), color = c.textPrimary)
    }
}

/**
 * A draft-holding numeric field: free typing while focused, [onCommit] on blur
 * or Done, so a half-typed number never reaches the store.
 */
@Composable
internal fun DraftField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCommit: () -> Unit = {},
    onFocus: () -> Unit = {},
    style: TextStyle,
    color: Color = Vectra.colors.textPrimary,
    shape: Shape = Radius.Row,
    background: Color = Vectra.colors.surfaceSecondary,
    border: Color? = Vectra.colors.borderSubtle,
    keyboardType: KeyboardType = KeyboardType.Decimal,
    placeholder: String? = null,
    align: TextAlign = TextAlign.Center,
    contentDescription: String? = null,
    singleLine: Boolean = true,
) {
    val c = Vectra.colors
    val focus = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val commit by rememberUpdatedState(onCommit)
    val gotFocus by rememberUpdatedState(onFocus)
    val wasFocused = remember { booleanArrayOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .onFocusChanged {
                if (it.isFocused && !wasFocused[0]) gotFocus()
                if (!it.isFocused && wasFocused[0]) commit()
                wasFocused[0] = it.isFocused
            }
            .background(background, shape)
            .then(if (border != null || focused) Modifier.border(1.dp, if (focused) c.accentPrimary else border!!, shape) else Modifier)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        singleLine = singleLine,
        textStyle = style.resolve().copy(color = color, textAlign = align),
        cursorBrush = SolidColor(c.accentPrimary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        interactionSource = interaction,
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 8.dp), contentAlignment = if (align == TextAlign.Center) Alignment.Center else Alignment.CenterStart) {
                if (value.isEmpty() && placeholder != null) {
                    VText(placeholder, style = style, color = c.textMuted, align = align, modifier = Modifier.fillMaxWidth())
                }
                inner()
            }
        },
    )
}

/** Emerald/red/amber/grey fills used by grade and attendance bars. */
internal object Fills {
    val Emerald500 = Palette.Emerald500
    val Sky500 = Palette.Sky500
    val Amber500 = Palette.Amber500
    val Orange500 = Palette.Orange500
    val Red500 = Palette.Red500
    val Slate400 = Palette.Slate400
}

internal enum class PillKind { Accent, Outline, Ghost }

/** The tracker's buttons: a pill by default, reshaped per use (rounded rects, 48dp submits). */
@Composable
internal fun TrackerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: PillKind = PillKind.Accent,
    shape: Shape = CircleShape,
    height: Dp = 36.dp,
    horizontalPadding: Dp = 16.dp,
    enabled: Boolean = true,
    content: @Composable RowScope.(ink: Color) -> Unit,
) {
    val c = Vectra.colors
    val interaction = remember { MutableInteractionSource() }
    val ink = when (kind) {
        PillKind.Accent -> Color.White
        PillKind.Outline -> c.textPrimary
        PillKind.Ghost -> c.textSecondary
    }
    val surface = when (kind) {
        PillKind.Accent -> if (enabled) Modifier.pillDepth(shape, PillTone.Accent) else Modifier.background(c.accentPrimary, shape)
        PillKind.Outline -> Modifier.pillGlass(shape)
        PillKind.Ghost -> Modifier
    }
    Row(
        modifier
            .then(if (enabled) Modifier.pressScale(interaction) else Modifier)
            .alpha(if (enabled) 1f else 0.5f)
            .then(surface)
            .clip(shape)
            .tappable(enabled = enabled, role = Role.Button, highlight = false, interactionSource = interaction, onClick = onClick)
            .height(height)
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(ink) }
}
