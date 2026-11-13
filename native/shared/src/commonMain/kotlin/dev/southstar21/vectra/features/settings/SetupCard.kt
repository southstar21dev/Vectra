package dev.southstar21.vectra.features.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.ButtonSize
import dev.southstar21.vectra.core.designsystem.components.ButtonVariant
import dev.southstar21.vectra.core.designsystem.components.HairlineDivider
import dev.southstar21.vectra.core.designsystem.components.SettingsLink
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Radius
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext

/**
 * One feature on the setup card. [done] null while it's still unknown (loading): such an item is
 * left out rather than flashing in as missing.
 */
@Immutable
class SetupItem(
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val subtitle: String,
    val done: Boolean?,
    val actionLabel: String,
    val action: () -> Unit,
)

/** Missing items shown before "Show N more". */
private const val FirstShown = 4

/**
 * "Finish setting up Vectra": what a new account hasn't turned on yet, each with the one tap that
 * turns it on. Items drop off as they're done; [onDismiss] hides the card for good (About brings it back).
 */
@Composable
fun SetupCard(items: List<SetupItem>, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    val known = items.filter { it.done != null }
    val missing = known.filter { it.done == false }
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) missing else missing.take(FirstShown)
    Column(modifier.fillMaxWidth().glass(Radius.Card).animateContentSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                VText("Finish setting up Vectra", style = vtext(TypeScale.BodyLarge, Weight.Extrabold, Tracking.Tight), color = c.textPrimary)
                val left = missing.size
                VText(
                    "${known.size - left} of ${known.size} set up · $left ${if (left == 1) "thing" else "things"} left",
                    Modifier.padding(top = 2.dp),
                    vtext(TypeScale.Caption),
                    c.textSecondary,
                )
            }
            Box(
                Modifier.size(44.dp).tappable(CircleShape, onClickLabel = "Dismiss setup tips", onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                VIcon(RemixIcons.CloseLine, size = 18.dp, tint = c.textMuted, contentDescription = "Dismiss")
            }
        }
        SetupProgress(done = known.size - missing.size, total = known.size, Modifier.padding(horizontal = 16.dp).padding(top = 10.dp, bottom = 6.dp))
        shown.forEachIndexed { i, item ->
            if (i > 0) HairlineDivider(Modifier.padding(start = 52.dp))
            SetupRow(item, primary = i == 0)
        }
        if (missing.size > FirstShown) {
            SettingsLink(
                if (expanded) "Show less" else "Show ${missing.size - FirstShown} more",
                { expanded = !expanded },
                Modifier.padding(start = 12.dp, bottom = 8.dp),
            )
        } else {
            Box(Modifier.height(6.dp))
        }
    }
}

/** One segment per feature, the set-up ones filled in the accent. */
@Composable
private fun SetupProgress(done: Int, total: Int, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    Row(modifier.fillMaxWidth().height(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { i ->
            Box(Modifier.weight(1f).height(4.dp).background(if (i < done) c.accentPrimary else c.borderSubtle, CircleShape))
        }
    }
}

@Composable
private fun SetupRow(item: SetupItem, primary: Boolean) {
    val c = Vectra.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { VIcon(item.icon, size = 18.dp, tint = item.tint) }
        Column(Modifier.weight(1f)) {
            VText(item.title, style = vtext(TypeScale.Body, Weight.Semibold), color = c.textPrimary)
            VText(item.subtitle, Modifier.padding(top = 2.dp), vtext(TypeScale.Caption), c.textSecondary)
        }
        VButton(item.actionLabel, item.action, variant = if (primary) ButtonVariant.Primary else ButtonVariant.Outline, size = ButtonSize.Sm)
    }
}
