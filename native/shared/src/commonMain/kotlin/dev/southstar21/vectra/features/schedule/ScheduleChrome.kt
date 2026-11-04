package dev.southstar21.vectra.features.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.ButtonSize
import dev.southstar21.vectra.core.designsystem.components.SheetInputHeight
import dev.southstar21.vectra.core.designsystem.components.Spinner
import dev.southstar21.vectra.core.designsystem.components.SurfaceGutter
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.VInput
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.LocalContentColor
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.insetShadow
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.rgba
import dev.southstar21.vectra.core.designsystem.theme.specularBorder
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.designsystem.theme.Radius

/*
 * Pieces shared by the schedule's pushed screens (class and item editors, Neptun sync)
 * on top of the design-system chrome, plus the schedule's own glass and chip styles.
 */

/** Body padding of an editor screen: the gutter, a little air under the bar. */
internal val EditorBodyPadding = PaddingValues(start = SurfaceGutter, end = SurfaceGutter, top = 12.dp, bottom = 24.dp)

/**
 * Leaves the screen at most once, however many paths ask to (a save, a delete, the record
 * vanishing from the repository); a second pop would close the screen underneath.
 */
@Composable
internal fun rememberExitOnce(onBack: () -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onBack)
    val done = remember { booleanArrayOf(false) }
    return remember {
        {
            if (!done[0]) {
                done[0] = true
                latest()
            }
        }
    }
}

/** Glass card flush with the page; light mode trades the glass band shadow for an even halo. */
@Composable
internal fun Modifier.flushGlass(shape: Shape): Modifier {
    val c = Vectra.colors
    val shadows = if (c.isDark) c.glassShadow else listOf(
        BoxShadow(blur = 24f, spread = -2f, color = rgba(15, 23, 42, 0.11f)),
        BoxShadow(y = 1f, blur = 2f, color = rgba(15, 23, 42, 0.05f)),
        BoxShadow(y = 8f, blur = 20f, spread = -6f, color = rgba(15, 23, 42, 0.14f)),
        BoxShadow(y = 1f, color = rgba(255, 255, 255, 0.9f), inset = true),
    )
    return this
        .boxShadow(shape, shadows)
        .background(c.glassBg, shape)
        .insetShadow(shape, shadows)
        .specularBorder(shape, c.glassBorder, c.glassBorderSpecular)
}

/** A titled section: heading and its cards 12dp apart. */
@Composable
internal fun SheetSection(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** A quiet accent text button. */
@Composable
internal fun AccentTextButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Vectra.colors
    Box(
        Modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .tappable(Radius.Chip, enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) { VText(text, style = vtext(TypeScale.Caption, Weight.Semibold), color = c.accentPrimary) }
}

/** The full-width submit docked in an editor's bottom bar. */
@Composable
internal fun EditorSubmitButton(enabled: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    VButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp), size = ButtonSize.Lg, enabled = enabled, content = content)
}

/** Spinner while busy, then the label. */
@Composable
internal fun SubmitLabel(busy: Boolean, text: String) {
    if (busy) Spinner(size = 16.dp, color = LocalContentColor.current, thickness = 2.dp)
    VText(text, style = vtext(TypeScale.Body, Weight.Bold), maxLines = 1)
}

/** A sentence-case text field at form height. */
@Composable
internal fun EditorTextInput(value: String, onChange: (String) -> Unit, placeholder: String, maxLength: Int? = null) {
    VInput(
        value = value,
        onValueChange = { if (maxLength == null || it.length <= maxLength) onChange(it) },
        placeholder = placeholder,
        height = SheetInputHeight,
        textSize = TypeScale.BodyLarge,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
}

/** Two fields side by side with a hairline between them. */
@Composable
internal fun TwoColumns(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.weight(1f)) { left() }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Vectra.colors.borderSubtle))
        Box(Modifier.weight(1f)) { right() }
    }
}

/** A rounded outline chip button. */
@Composable
internal fun ChipButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val c = Vectra.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(interaction)
            .background(c.surfaceOverlay, CircleShape)
            .border(1.dp, c.borderSubtle, CircleShape)
            .tappable(CircleShape, interactionSource = interaction, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

