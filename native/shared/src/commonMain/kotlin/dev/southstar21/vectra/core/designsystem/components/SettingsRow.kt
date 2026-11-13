package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext

/**
 * One settings row: a bare line icon in its tint (no tile behind it), title and subtitle, and a
 * control on the right. With [onClick] the whole row is the tap target and, without a control,
 * ends in a chevron. [enabled] false greys it out (an action with nothing to act on).
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    titleStyle: TextStyle = vtext(TypeScale.Body, Weight.Semibold, lineHeight = 17.5.sp),
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    control: (@Composable () -> Unit)? = null,
) {
    val c = Vectra.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.tappable(enabled = enabled, role = Role.Button, onClickLabel = title, onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            VIcon(icon, size = 18.dp, tint = iconTint)
        }
        Column(Modifier.weight(1f)) {
            VText(title, style = titleStyle, color = if (danger) c.tints.red.text else c.textPrimary)
            if (subtitle != null) VText(subtitle, Modifier.padding(top = 2.dp), vtext(TypeScale.Caption), c.textSecondary)
        }
        when {
            control != null -> control()
            onClick != null -> VIcon(RemixIcons.ArrowRightSLine, size = 18.dp, tint = c.textMuted)
        }
    }
}

/** A row's action as a text link in the accent ("Edit", "Change", "Set up"): quieter than a button. */
@Composable
fun SettingsLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    val c = Vectra.colors
    Box(
        modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 36.dp)
            .tappable(enabled = enabled && !busy, role = Role.Button, highlight = false, pressedScale = 0.95f, onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) InlineLoader(size = 14.dp, tint = c.accentPrimary)
        else VText(text, style = vtext(TypeScale.Body, Weight.Bold), color = if (enabled) c.accentPrimary else c.textMuted)
    }
}

/**
 * A section's quiet footer action ("Send a test reminder") with what it does beside it: a link,
 * not a row of its own.
 */
@Composable
fun SettingsFooterLink(icon: ImageVector, text: String, caption: String, onClick: () -> Unit, modifier: Modifier = Modifier, busy: Boolean = false) {
    val c = Vectra.colors
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .defaultMinSize(minHeight = 36.dp)
                .tappable(enabled = !busy, role = Role.Button, highlight = false, pressedScale = 0.96f, onClick = onClick),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) InlineLoader(size = 14.dp, tint = c.accentPrimary) else VIcon(icon, size = 14.dp, tint = c.accentPrimary)
            VText(text, style = vtext(TypeScale.Caption, Weight.Bold), color = c.accentPrimary, maxLines = 1)
        }
        VText(caption, Modifier.weight(1f), vtext(TypeScale.Caption), c.textMuted)
    }
}
