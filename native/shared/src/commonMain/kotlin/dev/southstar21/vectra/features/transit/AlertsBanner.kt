package dev.southstar21.vectra.features.transit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Radius

private val AlertShape = Radius.Panel

/**
 * One alert as a single collapsed line: icon, the headline truncated to one
 * line, and a chevron. Tapping it opens the full headline, the description and
 * the bkk.hu link; tapping again folds it back.
 */
@Composable
private fun AlertCard(alert: TransitAlert) {
    val c = Vectra.colors
    val amber = c.tints.amber.text
    var expanded by rememberSaveable(alert.id) { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "alertChevron")
    val uri = LocalUriHandler.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Amber500.copy(alpha = 0.10f), AlertShape)
            .border(1.dp, Palette.Amber500.copy(alpha = 0.25f), AlertShape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .tappable(AlertShape, role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VIcon(RemixIcons.AlertLine, tint = amber)
            VText(
                alert.header,
                Modifier.weight(1f),
                vtext(TypeScale.Body, Weight.Bold, lineHeight = 17.5.sp),
                amber,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
            )
            VIcon(RemixIcons.ArrowDownSLine, Modifier.rotate(chevron), tint = amber.copy(alpha = 0.7f))
        }
        if (expanded) {
            Column(Modifier.padding(start = 44.dp, end = 16.dp, bottom = 14.dp)) {
                if (alert.description.isNotEmpty()) {
                    VText(alert.description, style = vtext(TypeScale.Caption, lineHeight = 19.5.sp), color = amber.copy(alpha = 0.7f))
                }
                val url = alert.url
                if (!url.isNullOrEmpty()) {
                    Row(
                        Modifier
                            .padding(top = 8.dp)
                            .tappable(Radius.Chip, role = Role.Button) {
                                runCatching { uri.openUri(url) }
                            },
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VText(
                            "Details on bkk.hu",
                            style = vtext(TypeScale.Caption, Weight.Semibold).copy(textDecoration = TextDecoration.Underline),
                            color = c.tints.blue.text,
                        )
                        VIcon(RemixIcons.ExternalLinkLine, size = 12.dp, tint = c.tints.blue.text)
                    }
                }
            }
        }
    }
}

/**
 * Alert rows for the live-position sheet: each collapsed to one line until
 * tapped. Renders nothing when there are no active alerts. No section heading:
 * the amber rows are self-evidently alerts.
 */
@Composable
fun AlertsSection(alerts: List<TransitAlert>, modifier: Modifier = Modifier) {
    if (alerts.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        alerts.forEach { a -> key(a.id) { AlertCard(a) } }
    }
}
