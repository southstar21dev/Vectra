package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.LocalContentColor
import dev.southstar21.vectra.core.designsystem.theme.LocalTextStyle
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.PillTone
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.pillDepth
import dev.southstar21.vectra.core.designsystem.theme.pillGlass
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.designsystem.theme.tappable

/** Button colour/style variants. */
enum class ButtonVariant { Default, Primary, Emerald, Success, Blue, Gray, Destructive, Outline, Secondary, Ghost, Link }

enum class ButtonSize { Default, Sm, Lg, Icon }

@Composable
fun VButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Default,
    size: ButtonSize = ButtonSize.Default,
    enabled: Boolean = true,
    contentDescription: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = Vectra.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(if (size == ButtonSize.Sm) 8.dp else 12.dp)
    val pill = when (variant) {
        ButtonVariant.Default, ButtonVariant.Primary -> PillTone.Accent
        ButtonVariant.Emerald, ButtonVariant.Success -> PillTone.Emerald
        ButtonVariant.Blue -> PillTone.Blue
        ButtonVariant.Gray -> PillTone.Gray
        ButtonVariant.Destructive -> PillTone.Destructive
        else -> null
    }
    val ink = when {
        pill != null -> Color.White
        variant == ButtonVariant.Outline || variant == ButtonVariant.Secondary -> colors.textPrimary
        variant == ButtonVariant.Ghost -> colors.textSecondary
        else -> colors.accentPrimary
    }
    val surface = when {
        // Disabled pills drop the depth recipe and keep the flat fill.
        pill != null && !enabled -> Modifier.background(
            when (pill) {
                PillTone.Accent -> colors.accentPrimary
                PillTone.Emerald -> Palette.Emerald600
                PillTone.Blue -> Palette.Blue600
                PillTone.Gray -> Palette.Slate600
                PillTone.Destructive -> Palette.Red600
            },
            shape,
        )
        pill != null -> Modifier.pillDepth(shape, pill)
        variant == ButtonVariant.Outline -> Modifier.pillGlass(shape)
        variant == ButtonVariant.Secondary -> Modifier.background(colors.surfaceOverlay, shape).border(1.dp, colors.borderSubtle, shape)
        else -> Modifier
    }
    val sizing = when {
        variant == ButtonVariant.Link -> Modifier
        // Minimum heights, so labels still fit when the system font size is raised.
        size == ButtonSize.Default -> Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 6.dp)
        size == ButtonSize.Sm -> Modifier.heightIn(min = 32.dp).padding(horizontal = 12.dp, vertical = 4.dp)
        size == ButtonSize.Lg -> Modifier.heightIn(min = 48.dp).padding(horizontal = 24.dp, vertical = 8.dp)
        else -> Modifier.size(40.dp)
    }
    val text = vtext(
        if (size == ButtonSize.Lg) TypeScale.Body else TypeScale.Caption,
        if (pill != null) Weight.Bold else Weight.Normal,
    )
    val fade by animateFloatAsState(if (enabled) 1f else 0.5f, tween(150, easing = VectraEasing.Fade), label = "buttonAlpha")
    // Filled pills answer a press by shrinking; flat variants also get a highlight in their shape.
    Row(
        modifier = modifier
            .then(if (enabled) Modifier.pressScale(interaction, 0.97f) else Modifier)
            .graphicsLayer { alpha = fade }
            .then(surface)
            .tappable(
                shape,
                enabled = enabled,
                onClickLabel = contentDescription,
                highlight = pill == null,
                interactionSource = interaction,
                onClick = onClick,
            )
            .then(sizing)
            .defaultMinSize(minWidth = if (size == ButtonSize.Icon) 40.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides ink, LocalTextStyle provides text) {
            content()
        }
    }
}

/** Convenience for the common icon + label button. */
@Composable
fun VButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Default,
    size: ButtonSize = ButtonSize.Default,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    VButton(onClick, modifier, variant, size, enabled && !loading) {
        if (loading) Spinner(size = 16.dp, color = LocalContentColor.current)
        else if (icon != null) VIcon(icon)
        VText(text, maxLines = 1)
    }
}
