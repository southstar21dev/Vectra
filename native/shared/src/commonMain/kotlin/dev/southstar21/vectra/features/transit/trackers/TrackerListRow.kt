package dev.southstar21.vectra.features.transit.trackers

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.StatusLabel
import dev.southstar21.vectra.core.designsystem.components.StatusTone
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VSelect
import dev.southstar21.vectra.core.designsystem.components.VTabs
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.features.transit.MaxTrackerStops
import dev.southstar21.vectra.features.transit.StopDirectionOptions
import dev.southstar21.vectra.features.transit.data.TrackerStop
import dev.southstar21.vectra.features.transit.hiddenFootnote
import dev.southstar21.vectra.features.transit.resolveDirectionValue
import dev.southstar21.vectra.features.transit.toSelectOptions

/*
 * List furniture shared by the tracker sheets — a tappable
 * row inside a FormGroup, a direction picker, the "n of 4" pill.
 */

@Composable
internal fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    disabled: Boolean = false,
    /** Draws a check on the right in place of `trailing`. */
    selected: Boolean? = null,
    /** Draws a chevron on the right: the row navigates somewhere. */
    navigates: Boolean = false,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
) {
    val c = Vectra.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        selected == true -> c.accentPrimary.copy(alpha = 0.08f)
        onClick != null && pressed && !disabled -> c.surfaceOverlay
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(bg)
            .then(
                if (onClick != null) Modifier.tappable(enabled = !disabled, role = Role.Button, highlight = false, interactionSource = interaction, onClick = onClick)
                else Modifier,
            )
            .alpha(if (disabled) 0.5f else 1f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) Box(contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f)) {
            if (titleContent != null) titleContent()
            else VText(title, style = vtext(TypeScale.Body, Weight.Semibold), color = c.textPrimary, maxLines = 2)
            if (subtitle != null) {
                VText(subtitle, Modifier.padding(top = 2.dp), vtext(TypeScale.Caption), c.textSecondary, maxLines = 1)
            }
        }
        if (trailing != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
        if (selected == true) VIcon(RemixIcons.CheckLine, tint = c.accentPrimary)
        if (navigates) VIcon(RemixIcons.ArrowRightSLine, tint = c.textMuted)
    }
}

/** Which direction a tracked stop watches; empty means every direction. */
@Composable
internal fun DirectionSelect(
    value: String,
    directionOptions: StopDirectionOptions,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    stopName: String? = null,
) {
    val resolvedValue = remember(value, directionOptions) { resolveDirectionValue(value, directionOptions.options) }
    val options = remember(directionOptions, resolvedValue) { directionOptions.toSelectOptions(resolvedValue) }
    val title = stopName?.takeIf { it.isNotBlank() }?.let { "Direction from $it" }
    val footnote = remember(directionOptions) { directionOptions.hiddenFootnote() }
    VSelect(
        value = resolvedValue,
        options = options,
        onChange = onChange,
        modifier = modifier,
        title = title,
        footnote = footnote,
    )
}

/** "2 / 4" count of tracked stops; its dot turns amber at the limit. */
@Composable
internal fun StopCountPill(count: Int, modifier: Modifier = Modifier) {
    StatusLabel("$count / $MaxTrackerStops", modifier, tone = if (count >= MaxTrackerStops) StatusTone.Amber else StatusTone.Neutral)
}

/** Centred status block for loading, errors and empty lists inside a sheet. */
@Composable
internal fun ListStatus(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** The tracked stops as the sheet's segmented pill; switching retargets the live view. */
@Composable
internal fun TrackedStopTabs(stops: List<TrackerStop>, activeStopId: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    if (stops.size < 2) return
    val active = activeStopId ?: stops[0].stopId
    VTabs(
        selected = active,
        options = stops.map { it.stopId },
        onSelect = { id -> if (id != activeStopId) onSelect(id) },
        modifier = modifier.fillMaxWidth(),
        columns = minOf(stops.size, 4),
    ) { id, _ ->
        VText(stops.firstOrNull { it.stopId == id }?.stopName.orEmpty(), maxLines = 1)
    }
}
