package dev.southstar21.vectra.features.transit.trackers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext

/** The badge column of every line row: one width, so the names beside it start in one column. */
internal val LineBadgeColumn = 48.dp

/**
 * A line's badge in the fixed [LineBadgeColumn]. A wider badge ("28/28A") grows out of it evenly
 * instead of pushing the text over.
 */
@Composable
internal fun LineBadgeSlot(modifier: Modifier = Modifier, badge: @Composable () -> Unit) {
    Box(modifier.width(LineBadgeColumn).wrapContentWidth(Alignment.CenterHorizontally, unbounded = true), contentAlignment = Alignment.Center) {
        badge()
    }
}

/**
 * The words of a line row: [title] (the start, or the stop), "to [toLine]" on its own line, then
 * [meta] (the vehicle type, a "Tracked" chip). Names wrap rather than cut.
 */
@Composable
internal fun LineRowText(title: String, toLine: String? = null, meta: (@Composable () -> Unit)? = null) {
    val c = Vectra.colors
    VText(title, style = vtext(TypeScale.Body, Weight.Semibold), color = c.textPrimary, maxLines = 2)
    if (!toLine.isNullOrEmpty()) {
        VText("→ $toLine", Modifier.padding(top = 1.dp), vtext(TypeScale.Caption, Weight.Medium), c.textSecondary, maxLines = 2)
    }
    if (meta != null) Box(Modifier.padding(top = 3.dp)) { meta() }
}

/**
 * One line with its termini, the same in every list: the [badge] in a fixed column, the start as
 * [title], "→ [toLine]" under it and [meta] last. Tapping, a check ([selected]) and a chevron
 * ([navigates]) come from [ListRow].
 */
@Composable
internal fun LineRow(
    badge: @Composable () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    toLine: String? = null,
    meta: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    navigates: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    ListRow(
        title,
        modifier,
        onClick = onClick,
        selected = selected,
        navigates = navigates,
        leading = { LineBadgeSlot(badge = badge) },
        titleContent = { LineRowText(title, toLine, meta) },
        trailing = trailing,
    )
}
