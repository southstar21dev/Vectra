package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.mixTransparent
import dev.southstar21.vectra.core.designsystem.theme.resolve
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.designsystem.theme.Radius

/** Glass text field with an accent focus ring. [height] is a minimum; large text grows it. */
@Composable
fun VInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    height: Dp = 40.dp,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textSize: Pair<TextUnit, TextUnit> = TypeScale.Body,
) {
    val c = Vectra.colors
    val shape = Radius.Control
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(150, easing = VectraEasing.Fade), label = "inputFocus")
    val iconTint by animateColorAsState(if (focused) c.accentPrimary else c.textMuted, tween(150), label = "inputIcon")
    val style = vtext(textSize).resolve().copy(color = c.textPrimary)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .alpha(if (enabled) 1f else 0.5f)
            // Focus: accent border, 1dp ring and a soft accent glow.
            .then(
                if (ring > 0f) Modifier.boxShadow(
                    shape,
                    listOf(
                        BoxShadow(spread = 1f, color = c.accentPrimary.copy(alpha = c.accentPrimary.alpha * ring)),
                        BoxShadow(blur = 16f, color = c.accentPrimary.mixTransparent(20f * ring)),
                    ),
                ) else Modifier,
            )
            .glass(shape, hairlineInLight = true)
            .then(if (ring > 0f) Modifier.border(1.dp, c.accentPrimary.copy(alpha = c.accentPrimary.alpha * ring), shape) else Modifier),
        enabled = enabled,
        singleLine = singleLine,
        textStyle = style,
        cursorBrush = SolidColor(c.accentPrimary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        decorationBox = { inner ->
            Row(
                Modifier.heightIn(min = height).padding(start = 12.dp, end = if (trailing != null) 8.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) VIcon(icon, tint = iconTint)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) VText(placeholder, style = vtext(textSize), color = c.textMuted, maxLines = 1)
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}

/** Divider in the border-subtle colour. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().height(1.dp).background(Vectra.colors.borderSubtle))
