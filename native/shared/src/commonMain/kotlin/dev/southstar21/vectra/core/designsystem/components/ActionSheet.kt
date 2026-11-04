package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.designsystem.theme.Radius

class SheetAction(val label: String, val icon: ImageVector, val destructive: Boolean = false, val onClick: () -> Unit)

/** A short list of actions for one thing (a long-press menu), shown in a sheet. */
@Composable
fun ActionSheet(open: Boolean, onDismiss: () -> Unit, title: String?, actions: List<SheetAction>, subtitle: String? = null) {
    VSheet(open, onDismiss) {
        if (title != null) SheetHeader(title, onDismiss, subtitle = subtitle)
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
            for (action in actions) {
                val c = Vectra.colors
                val tint = if (action.destructive) c.tints.red.text else c.textPrimary
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .tappable(Radius.Row, haptic = { if (action.destructive) reject() else tick() }) {
                            onDismiss()
                            action.onClick()
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VIcon(action.icon, size = 20.dp, tint = if (action.destructive) tint else c.textSecondary)
                    VText(action.label, style = vtext(TypeScale.BodyLarge, Weight.Medium), color = tint)
                }
            }
        }
    }
}
